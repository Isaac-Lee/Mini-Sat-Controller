package msc.domain.tasking;

import static msc.domain.shared.Checks.*;

import java.util.Objects;
import java.util.Optional;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

/** Request revision fences prior plans; persistence version counts lifecycle writes separately. */
public record ObservationRequest(
    RequestId id,
    Optional<AoiId> resolvedAoi,
    long revision,
    String fulfillmentCriteria,
    Optional<MissionInstant> deadline,
    int requestedPriority,
    InteractionPreference interactionPreference,
    Status status) {
  public enum InteractionPreference {
    AUTO,
    ASSISTED,
    ADVANCED
  }

  public enum Status {
    RECEIVED,
    CLARIFICATION_NEEDED,
    ACCEPTED,
    SCHEDULED,
    PARTIALLY_FULFILLED,
    FULFILLED,
    REJECTED,
    EXPIRED,
    CANCELLED
  }

  public ObservationRequest {
    Objects.requireNonNull(id);
    Objects.requireNonNull(resolvedAoi);
    positive(revision);
    text(fulfillmentCriteria);
    Objects.requireNonNull(deadline).ifPresent(MissionInstant::requireTai);
    Objects.requireNonNull(interactionPreference);
    Objects.requireNonNull(status);
    if (requestedPriority < 0 || requestedPriority > 100)
      throw new IllegalArgumentException("Priority must be 0..100");
    if (java.util.Set.of(
                Status.ACCEPTED, Status.SCHEDULED, Status.PARTIALLY_FULFILLED, Status.FULFILLED)
            .contains(status)
        && resolvedAoi.isEmpty())
      throw new IllegalArgumentException("Accepted work requires a resolved AOI");
  }

  /** Compatibility constructor for already-resolved foundation callers. */
  public ObservationRequest(
      RequestId id,
      AoiId area,
      long revision,
      String criteria,
      Optional<MissionInstant> deadline,
      int priority,
      InteractionPreference preference,
      Status status) {
    this(id, Optional.of(area), revision, criteria, deadline, priority, preference, status);
  }

  public boolean terminal() {
    return java.util.Set.of(Status.FULFILLED, Status.REJECTED, Status.EXPIRED, Status.CANCELLED)
        .contains(status);
  }

  private ObservationRequest state(Status next, Optional<AoiId> area, long specificationRevision) {
    return new ObservationRequest(
        id,
        area,
        specificationRevision,
        fulfillmentCriteria,
        deadline,
        requestedPriority,
        interactionPreference,
        next);
  }

  public ObservationRequest accept() {
    return accept(
        resolvedAoi.orElseThrow(() -> new IllegalStateException("AOI has not been resolved")));
  }

  public ObservationRequest accept(AoiId area) {
    if (status != Status.RECEIVED && status != Status.CLARIFICATION_NEEDED)
      throw new IllegalStateException("Only unresolved requests can be accepted");
    return state(Status.ACCEPTED, Optional.of(area), revision);
  }

  public ObservationRequest clarificationNeeded() {
    if (status != Status.RECEIVED)
      throw new IllegalStateException("Only received requests need initial clarification");
    return state(Status.CLARIFICATION_NEEDED, Optional.empty(), revision);
  }

  public ObservationRequest scheduled() {
    if (status != Status.ACCEPTED
        && status != Status.SCHEDULED
        && status != Status.PARTIALLY_FULFILLED)
      throw new IllegalStateException("Request is not eligible for scheduling");
    return state(
        status == Status.PARTIALLY_FULFILLED ? status : Status.SCHEDULED, resolvedAoi, revision);
  }

  public ObservationRequest fulfilled(boolean complete) {
    if (status != Status.ACCEPTED
        && status != Status.SCHEDULED
        && status != Status.PARTIALLY_FULFILLED)
      throw new IllegalStateException("Request cannot accept fulfillment evidence");
    return state(complete ? Status.FULFILLED : Status.PARTIALLY_FULFILLED, resolvedAoi, revision);
  }

  public ObservationRequest cancel() {
    if (terminal()) throw new IllegalStateException("Terminal request cannot be cancelled");
    return state(Status.CANCELLED, resolvedAoi, Math.addExact(revision, 1));
  }

  public ObservationRequest expire(MissionInstant now) {
    if (terminal() || deadline.isEmpty() || now.compareTo(deadline.get()) < 0)
      throw new IllegalStateException("Request is not expired");
    return state(Status.EXPIRED, resolvedAoi, Math.addExact(revision, 1));
  }
}
