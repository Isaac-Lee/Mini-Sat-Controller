package msc.services.tasking;

import java.util.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.ObservationRequest;
import msc.domain.tasking.ObservationRequest.Status;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.stereotype.Component;

@Component
public final class RequestWorkflow implements EventHandler {
  private final StateStore store;
  private final Json json;
  private final Clock clock;

  public RequestWorkflow(StateStore store, Json json, Clock clock) {
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  public StateStore.State<RequestDetails> submit(
      String id, String owner, Submission input, StateStore.State<RequestDetails> previous) {
    var now = clock.now();
    if (input.deadline().isPresent() && now.compareTo(input.deadline().get()) >= 0)
      throw ApiException.invalid("Deadline must be in the future");
    long revision = previous == null ? 1 : Math.addExact(previous.body().request().revision(), 1);
    if (previous != null && previous.body().request().terminal())
      throw ApiException.conflict("Terminal request cannot be revised");
    // Default: complete requested area, no cloud constraint. The effective criteria are returned to
    // the user.
    var criteria = input.criteria().orElse(new Criteria(1, 1));
    var request =
        new ObservationRequest(
            new RequestId(id),
            Optional.empty(),
            revision,
            json.write(criteria),
            input.deadline(),
            input.priority(),
            input.preference(),
            Status.RECEIVED);
    store.create("request-input", id + ":" + revision, input);
    Optional<Area> effectiveArea =
        input
            .area()
            .map(
                area -> {
                  boolean approvedChoice =
                      previous != null
                          && previous.body().request().status() == Status.CLARIFICATION_NEEDED
                          && previous.body().clarificationOptions().contains(area);
                  if (approvedChoice) return area;
                  // Caller-supplied IDs/source strings cannot impersonate a globally approved area
                  // version.
                  return new Area(
                      "request-area:" + id + ":" + revision + ":" + json.fingerprint(area),
                      area.west(),
                      area.south(),
                      area.east(),
                      area.north(),
                      "request-input:" + id + ":" + revision);
                });
    if (effectiveArea.isPresent()) request = request.accept(new AoiId(effectiveArea.get().id()));
    var details =
        new RequestDetails(
            request,
            owner,
            input.target(),
            effectiveArea,
            criteria,
            previous == null ? now : previous.body().createdAt(),
            now,
            "",
            List.of());
    var saved =
        previous == null
            ? store.create("request", id, details)
            : store.update("request", id, previous.version(), details);
    if (previous != null)
      store.event(
          "ObservationRequestInvalidated",
          id,
          revision,
          UUID.randomUUID(),
          null,
          new RequestProgress(
              id, previous.body().request().revision(), "request-revised:" + revision));
    if (effectiveArea.isPresent()) accepted(details, null);
    else
      store.event(
          "ObservationIntentReceived",
          id,
          revision,
          UUID.randomUUID(),
          null,
          new IntentReceived(id, revision, input.target()));
    return saved;
  }

  private void accepted(RequestDetails details, ServiceEvent cause) {
    var request = details.request();
    store.event(
        "ObservationRequestAccepted",
        request.id().value(),
        request.revision(),
        cause == null ? UUID.randomUUID() : cause.correlationId(),
        cause == null ? null : cause.eventId(),
        new AcceptedRequest(
            request.id().value(),
            request.revision(),
            details.area().orElseThrow(),
            details.criteria(),
            request.deadline(),
            request.requestedPriority()));
  }

  private RequestDetails change(RequestDetails old, ObservationRequest next, String reason) {
    return new RequestDetails(
        next,
        old.owner(),
        old.target(),
        old.area(),
        old.criteria(),
        old.createdAt(),
        clock.now(),
        reason,
        old.clarificationOptions());
  }

  public StateStore.State<RequestDetails> cancel(StateStore.State<RequestDetails> current) {
    var old = current.body();
    if (old.request().terminal()) throw ApiException.conflict("Request is already terminal");
    var changed =
        change(
            old,
            old.request().cancel(),
            "Future tasking cancelled; this is not confirmation of onboard command cancellation");
    var saved = store.update("request", current.id(), current.version(), changed);
    store.event(
        "ObservationRequestInvalidated",
        current.id(),
        changed.request().revision(),
        UUID.randomUUID(),
        null,
        new RequestProgress(current.id(), old.request().revision(), "request-cancelled"));
    return saved;
  }

  @Override
  public void handle(ServiceEvent event) {
    switch (event.type()) {
      case "TargetResolved" ->
          resolve(json.convert(event.payload(), TargetResolution.class), event);
      case "ScheduleAssignmentCommitted" ->
          progress(json.convert(event.payload(), RequestProgress.class), event, true);
      case "PlanningRejected" ->
          progress(json.convert(event.payload(), RequestProgress.class), event, false);
      case "ProductQualityAssessed" ->
          quality(json.convert(event.payload(), QualityEvidence.class), event);
      // Compatibility with durable bindings declared by the initial runtime before this consumer
      // existed.
      // These are tasking outputs; the authoritative transition already happened before publishing
      // them.
      case "RequestFulfilled", "RequestPartiallyFulfilled" -> {}
      default -> throw new IllegalArgumentException("Unsupported tasking event: " + event.type());
    }
  }

  private StateStore.State<RequestDetails> current(String id, long revision) {
    store.lock("request:" + id);
    var found = store.find("request", id, RequestDetails.class);
    if (found.isEmpty()) throw ApiException.missing("Event references missing request");
    var value = found.get();
    var request = value.body().request();
    if (request.revision() != revision || request.terminal()) return null;
    if (request.deadline().isPresent() && clock.now().compareTo(request.deadline().get()) >= 0) {
      expire(value);
      return null;
    }
    return value;
  }

  private void resolve(TargetResolution result, ServiceEvent event) {
    var current = current(result.requestId(), result.revision());
    if (current == null || current.body().request().status() != Status.RECEIVED) return;
    var old = current.body();
    var request =
        result.area().isPresent()
            ? old.request().accept(new AoiId(result.area().get().id()))
            : old.request().clarificationNeeded();
    var updated =
        new RequestDetails(
            request,
            old.owner(),
            old.target(),
            result.area(),
            old.criteria(),
            old.createdAt(),
            clock.now(),
            result.reason(),
            result.candidates());
    store.update("request", current.id(), current.version(), updated);
    if (result.area().isPresent()) accepted(updated, event);
    else
      store.event(
          "ObservationClarificationNeeded",
          current.id(),
          request.revision(),
          event.correlationId(),
          event.eventId(),
          result);
  }

  private void progress(RequestProgress progress, ServiceEvent event, boolean scheduled) {
    var current = current(progress.requestId(), progress.revision());
    if (current == null) return;
    var old = current.body();
    if (old.area().isEmpty())
      throw ApiException.conflict("Planning progress before AOI acceptance");
    var request = scheduled ? old.request().scheduled() : old.request();
    store.update(
        "request",
        current.id(),
        current.version(),
        change(old, request, progress.evidenceReference()));
    // No feasible plan is not automatically a terminal rejection; replanning may use newer
    // evidence.
  }

  private void quality(QualityEvidence evidence, ServiceEvent event) {
    var current = current(evidence.requestId(), evidence.revision());
    if (current == null) return;
    var old = current.body();
    store.create("quality-evidence", event.eventId().toString(), evidence);
    boolean eligible =
        evidence.cloudFraction() <= old.criteria().maximumCloudFraction()
            && evidence.unionCoverageFraction() > 0;
    boolean complete =
        eligible && evidence.unionCoverageFraction() >= old.criteria().minimumCoverageFraction();
    var next = eligible ? old.request().fulfilled(complete) : old.request();
    store.update(
        "request",
        current.id(),
        current.version(),
        change(old, next, evidence.assessmentReference()));
    if (eligible)
      store.event(
          complete ? "RequestFulfilled" : "RequestPartiallyFulfilled",
          current.id(),
          next.revision(),
          event.correlationId(),
          event.eventId(),
          evidence);
  }

  public void expire(StateStore.State<RequestDetails> current) {
    var old = current.body();
    var next = old.request().expire(clock.now());
    store.update("request", current.id(), current.version(), change(old, next, "Deadline elapsed"));
    store.event(
        "ObservationRequestInvalidated",
        current.id(),
        next.revision(),
        UUID.randomUUID(),
        null,
        new RequestProgress(current.id(), old.request().revision(), "request-expired"));
  }
}
