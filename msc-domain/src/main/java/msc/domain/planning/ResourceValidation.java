package msc.domain.planning;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import msc.domain.shared.Ids.ResourceId;

/** Whole-timeline validation evidence; never per-request reservoir reservations. */
public record ResourceValidation(
    Status reservoirStatus,
    String trajectoryReference,
    List<ExternalReservation> externalReservations) {
  public enum Status {
    NOT_EVALUATED,
    VALIDATED,
    REJECTED
  }

  public enum ReservationState {
    TENTATIVE,
    CONFIRMED
  }

  public record ExternalReservation(
      ResourceId resourceId, String bookingReference, ReservationState state) {
    public ExternalReservation {
      Objects.requireNonNull(resourceId);
      text(bookingReference);
      Objects.requireNonNull(state);
    }
  }

  public ResourceValidation {
    Objects.requireNonNull(reservoirStatus);
    text(trajectoryReference);
    externalReservations = List.copyOf(externalReservations);
  }

  public static ResourceValidation notEvaluated() {
    return new ResourceValidation(Status.NOT_EVALUATED, "not-evaluated", List.of());
  }
}
