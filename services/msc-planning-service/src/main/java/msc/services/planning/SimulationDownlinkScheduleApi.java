package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.GroundContracts.*;
import msc.contracts.TaskingContracts.RequestDetails;
import msc.domain.planning.*;
import msc.domain.shared.Ids.*;
import msc.domain.time.TimeWindow;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V1 DOWNLINK operation derived from a selected imaging request and a confirmed booking. */
@RestController
public class SimulationDownlinkScheduleApi {
  public static final String MODEL = "SIMULATION_V1_OPERATION_REVIEW";

  public record Commit(
      String bookingId, TimeWindow window, long expectedScheduleVersion, String reviewReference) {
    public Commit {
      msc.domain.shared.Checks.text(bookingId);
      msc.domain.shared.Checks.text(reviewReference);
      Objects.requireNonNull(window);
      if (expectedScheduleVersion < 0)
        throw new IllegalArgumentException("Invalid schedule version");
    }
  }

  public record Decision(
      String environment,
      String evaluationModel,
      String runId,
      String sourceRunId,
      long requestRevision,
      String candidateId,
      String activityId,
      String sourceImageDecision,
      String reviewer,
      String reviewReference,
      Booking booking,
      PlanningInputs.Evidence catalog,
      PlanningResources.CandidateResult resources,
      PlanningRun.Snapshot proposal,
      MissionSchedule.Snapshot schedule) {}

  private final StateStore store;
  private final Json json;
  private final ServiceHttp http;
  private final Clock clock;
  private final JdbcTemplate db;
  private final JdbcScheduleRepository schedules;

  public SimulationDownlinkScheduleApi(
      StateStore store, Json json, ServiceHttp http, Clock clock, JdbcTemplate db) {
    this.store = store;
    this.json = json;
    this.http = http;
    this.clock = clock;
    this.db = db;
    schedules = new JdbcScheduleRepository(store, json, db);
  }

  @PostMapping("/api/planning/runs/{id}/simulation-downlink")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public JsonNode commit(
      @PathVariable String id,
      @RequestBody Commit request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-downlink-schedule:" + actor.getName();
    var body = Map.of("runId", id, "request", request);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var run = store.require("planning-run", id, PlanningRuns.Published.class).body();
    var attempt =
        store
            .require("planning-input-attempt", run.inputAttemptId(), PlanningInputs.Attempt.class)
            .body();
    var asset =
        attempt.assets().stream()
            .filter(a -> a.spacecraftId().equals(run.spacecraftId()))
            .findFirst()
            .orElseThrow();
    if (!id.equals(run.id())
        || !json.fingerprint(run)
            .equals(json.fingerprint(new PlanningRuns(store, json).derive(attempt, asset))))
      throw ApiException.invalid("Owned run source mismatch");
    String imageKey = run.requestId() + ":" + run.requestRevision();
    var image =
        store
            .require("simulation-v1-schedule", imageKey, SimulationScheduleApi.Committed.class)
            .body();
    if (!id.equals(image.runId()) || !"SIMULATION".equals(image.environment()))
      throw ApiException.conflict("Selected simulation imaging run required");
    var selected =
        run.run().candidates().stream()
            .filter(c -> c.id().value().equals(image.candidateId()))
            .findFirst()
            .orElseThrow();
    if (request.window().start().compareTo(selected.activity().window().end()) < 0)
      throw ApiException.invalid("Downlink must follow selected imaging activity");
    var operations =
        run.operations()
            .orElseThrow(() -> ApiException.invalid("Pinned operation catalogs required"));
    var catalogEvidence =
        Optional.ofNullable(
                operations
                    .catalogs()
                    .get(msc.contracts.MissionCatalogBindingContracts.Role.DOWNLINK))
            .orElseThrow(() -> ApiException.invalid("Pinned downlink catalog required"));
    var catalog = json.convert(catalogEvidence.value(), CatalogEntry.class);
    if (!"DOWNLINK".equals(catalog.template().operation()))
      throw ApiException.invalid("DOWNLINK catalog required");
    var booking =
        http.get(
            "ground-operations",
            "/internal/bookings/" + PlanningInputs.segment(request.bookingId()),
            Booking.class);
    if (!request.bookingId().equals(booking.id())
        || booking.status() != BookingStatus.CONFIRMED
        || !run.spacecraftId().equals(booking.request().spacecraftId())
        || request.window().start().compareTo(booking.request().window().start()) < 0
        || request.window().end().compareTo(booking.request().window().end()) > 0)
      throw ApiException.conflict("Confirmed matching booking must contain the downlink window");
    var live =
        http.get(
            "tasking",
            "/internal/requests/" + PlanningInputs.segment(run.requestId()),
            RequestDetails.class);
    if (!run.requestId().equals(live.request().id().value())
        || live.request().revision() != run.requestRevision()
        || live.request().terminal())
      throw ApiException.conflict("Observation request changed or ended");
    String operationId =
        UUID.nameUUIDFromBytes(
                json.fingerprint(body).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    var candidate =
        PlanCandidate.propose(
            new CandidateId(operationId),
            new PlanningRunId(operationId),
            selected.requestId(),
            selected.spacecraftId(),
            new ActivityId(operationId),
            request.window(),
            catalog.activity(),
            selected.phase(),
            selected.mode(),
            new FeasibilityEvaluation(
                FeasibilityEvaluation.Status.FEASIBLE, "simulation-v1-operation:" + operationId));
    // A separate derived resource proposal; never replace or append to the original imaging run.
    var source = run.run();
    var proposal =
        new PlanningRuns.Published(
            operationId,
            run.requestId(),
            run.requestRevision(),
            run.inputAttemptId(),
            run.spacecraftId(),
            run.sourceHashes(),
            catalogEvidence,
            run.simulationModel(),
            run.geometry(),
            new PlanningRun.Snapshot(
                new PlanningRunId(operationId),
                source.requestId(),
                source.startedAt(),
                source.inputs(),
                List.of(),
                List.of(candidate.snapshot()),
                List.of()),
            run.operations());
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          var work =
              db.queryForList(
                  "SELECT revision,invalidated_through,last_attempt_id FROM planning_work WHERE"
                      + " request_id=? FOR UPDATE",
                  run.requestId());
          if (work.size() != 1
              || ((Number) work.getFirst().get("revision")).longValue() != run.requestRevision()
              || ((Number) work.getFirst().get("invalidated_through")).longValue()
                  >= run.requestRevision()
              || !run.inputAttemptId().equals(work.getFirst().get("last_attempt_id")))
            throw ApiException.conflict("Selected request was invalidated");
          if (live.request().deadline().filter(d -> clock.now().compareTo(d) >= 0).isPresent())
            throw ApiException.conflict("Request deadline passed");
          var resources =
              new PlanningResources(json)
                  .capture(store, schedules, attempt, asset, proposal, clock.now())
                  .candidates()
                  .getFirst();
          if (!resources.issues().isEmpty()
              || resources.forecast().isEmpty()
              || resources.forecast().get().status() != ResourceValidation.Status.VALIDATED)
            throw ApiException.conflict(
                "Downlink resource or conflict checks failed: " + resources.issues());
          var scheduleKey = new ScheduleKey(candidate.spacecraftId(), request.window());
          var previous =
              schedules.latest(scheduleKey).orElseGet(() -> MissionSchedule.empty(scheduleKey));
          if (previous.version() != request.expectedScheduleVersion())
            throw ApiException.conflict("Schedule version changed");
          var next =
              previous
                  .commit(
                      candidate,
                      new AssignmentId(UUID.randomUUID().toString()),
                      previous.frozenUntil())
                  .snapshot();
          var committed =
              MissionSchedule.restore(
                  new MissionSchedule.Snapshot(
                      next.key(),
                      next.version(),
                      next.frozenUntil(),
                      next.activities(),
                      next.assignments(),
                      new ResourceValidation(
                          ResourceValidation.Status.VALIDATED,
                          "simulation-v1-operation:" + operationId,
                          List.of(
                              new ResourceValidation.ExternalReservation(
                                  new ResourceId("station:" + booking.request().stationId()),
                                  booking.id(),
                                  ResourceValidation.ReservationState.CONFIRMED)))));
          schedules.commit(request.expectedScheduleVersion(), committed);
          store.create("planning-activity-catalog", operationId, catalogEvidence);
          store.create(
              "planning-activity-operation-profiles", operationId, operations.resourceProfiles());
          var result =
              new Decision(
                  "SIMULATION",
                  MODEL,
                  operationId,
                  id,
                  run.requestRevision(),
                  operationId,
                  operationId,
                  imageKey,
                  actor.getName(),
                  request.reviewReference(),
                  booking,
                  catalogEvidence,
                  resources,
                  proposal.run(),
                  committed.snapshot());
          var saved = store.create("simulation-v1-operation", operationId, result);
          store.event(
              "ScheduleVersionCommitted",
              json.fingerprint(scheduleKey),
              committed.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({
    "/api/planning/simulation-operations/{activityId}",
    "/internal/planning/simulation-operations/{activityId}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Decision> read(@PathVariable String activityId) {
    return store.require("simulation-v1-operation", activityId, Decision.class);
  }
}
