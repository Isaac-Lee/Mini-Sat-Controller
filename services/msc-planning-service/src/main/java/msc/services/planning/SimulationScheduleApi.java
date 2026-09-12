package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.SimulationPlanningContracts;
import msc.contracts.TaskingContracts.*;
import msc.domain.planning.*;
import msc.domain.shared.Ids.*;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V1 operator-selected simulation schedule; precise FD and command release remain separate. */
@RestController
public class SimulationScheduleApi {
  public static final String MODEL = "SIMULATION_V1_SAMPLED_REVIEW";

  public record Commit(
      String candidateId,
      long cameraModelVersion,
      long expectedScheduleVersion,
      String reviewReference) {
    public Commit {
      msc.domain.shared.Checks.text(candidateId);
      msc.domain.shared.Checks.text(reviewReference);
      if (cameraModelVersion < 1 || expectedScheduleVersion < 0)
        throw new IllegalArgumentException("Invalid version");
    }
  }

  public record Committed(
      String environment,
      String evaluationModel,
      String runId,
      long requestRevision,
      String candidateId,
      String cameraAssessmentId,
      String reviewer,
      String reviewReference,
      List<String> deferredChecks,
      PlanningResources.CandidateResult resources,
      MissionSchedule.Snapshot schedule) {}

  private final StateStore store;
  private final Json json;
  private final ServiceHttp http;
  private final Clock clock;
  private final JdbcTemplate db;
  private final JdbcScheduleRepository schedules;

  public SimulationScheduleApi(
      StateStore store, Json json, ServiceHttp http, Clock clock, JdbcTemplate db) {
    this.store = store;
    this.json = json;
    this.http = http;
    this.clock = clock;
    this.db = db;
    schedules = new JdbcScheduleRepository(store, json, db);
  }

  @PostMapping("/api/planning/runs/{id}/simulation-commit")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public JsonNode commit(
      @PathVariable String id,
      @RequestBody Commit request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-schedule:" + actor.getName();
    var body = Map.of("runId", id, "request", request);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var run = store.require("planning-run", id, PlanningRuns.Published.class).body();
    var attempt =
        store
            .require("planning-input-attempt", run.inputAttemptId(), PlanningInputs.Attempt.class)
            .body();
    var assets =
        attempt.assets().stream().filter(a -> a.spacecraftId().equals(run.spacecraftId())).toList();
    if (!id.equals(run.id())
        || assets.size() != 1
        || !json.fingerprint(run)
            .equals(
                json.fingerprint(new PlanningRuns(store, json).derive(attempt, assets.getFirst()))))
      throw ApiException.invalid("Run no longer matches its owned source attempt");
    var asset = assets.getFirst();
    var model =
        json.convert(
            run.simulationModel().value().path("body"), SimulationPlanningContracts.Model.class);
    if (!"SIMULATION".equals(model.environment()))
      throw ApiException.invalid("Simulation model required");
    var candidate =
        run.run().candidates().stream()
            .filter(c -> c.id().value().equals(request.candidateId()))
            .findFirst()
            .orElseThrow(() -> ApiException.missing("Owned candidate not found"));
    String cameraId = json.fingerprint(List.of(id, request.cameraModelVersion()));
    var camera =
        store.require("planning-camera", cameraId, PlanningCameraApi.Assessment.class).body();
    if (!id.equals(camera.runId()) || !json.fingerprint(run).equals(camera.runSha256()))
      throw ApiException.invalid("Camera evidence belongs to another run");
    var evidence =
        camera.candidates().stream()
            .filter(c -> c.candidateId().equals(request.candidateId()))
            .findFirst()
            .orElseThrow(() -> ApiException.invalid("Candidate camera evidence missing"));
    if (evidence.cameraResult().path("body").path("computedSampleCount").asInt() < 1)
      throw ApiException.invalid("At least one computed camera sample is required for V1 review");
    var live =
        http.get(
            "tasking",
            "/internal/requests/" + PlanningInputs.segment(run.requestId()),
            RequestDetails.class);
    if (!live.request().id().value().equals(run.requestId())
        || live.request().revision() != run.requestRevision()
        || live.request().terminal()
        || live.request().status() == msc.domain.tasking.ObservationRequest.Status.SCHEDULED)
      throw ApiException.conflict("Request changed, ended or is already scheduled");
    var catalog = json.convert(run.catalog().value(), CatalogEntry.class);
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          var work =
              db.queryForList(
                  "SELECT revision,last_attempt_id,invalidated_through,status FROM planning_work"
                      + " WHERE request_id=? FOR UPDATE",
                  run.requestId());
          if (work.size() != 1
              || ((Number) work.getFirst().get("revision")).longValue() != run.requestRevision()
              || ((Number) work.getFirst().get("invalidated_through")).longValue()
                  >= run.requestRevision()
              || !run.inputAttemptId().equals(work.getFirst().get("last_attempt_id")))
            throw ApiException.conflict("Planning input attempt was superseded");
          String assignmentKey = run.requestId() + ":" + run.requestRevision();
          if (store.find("simulation-v1-schedule", assignmentKey, Committed.class).isPresent())
            throw ApiException.conflict("Request revision already has a simulation schedule");
          schedules.lockSpacecraft(candidate.spacecraftId());
          var now = clock.now();
          if (live.request().deadline().filter(d -> now.compareTo(d) >= 0).isPresent())
            throw ApiException.conflict("Request deadline passed");
          var resources =
              new PlanningResources(json)
                  .capture(store, schedules, attempt, asset, run, now).candidates().stream()
                      .filter(c -> c.candidateId().equals(request.candidateId()))
                      .findFirst()
                      .orElseThrow();
          if (!resources.issues().isEmpty()
              || resources.forecast().isEmpty()
              || resources.forecast().get().status() != ResourceValidation.Status.VALIDATED)
            throw ApiException.conflict(
                "Current resource or schedule checks did not pass: " + resources.issues());
          var scheduleKey =
              new ScheduleKey(candidate.spacecraftId(), candidate.activity().window());
          var previous =
              schedules.latest(scheduleKey).orElseGet(() -> MissionSchedule.empty(scheduleKey));
          if (previous.version() != request.expectedScheduleVersion())
            throw ApiException.conflict("Schedule version changed");
          String reference = "simulation-v1-schedule:" + assignmentKey;
          // Feasible here means accepted by the documented V1 simulation selection policy only.
          var selected =
              PlanCandidate.propose(
                  candidate.id(),
                  candidate.runId(),
                  candidate.requestId(),
                  candidate.spacecraftId(),
                  candidate.activity().id(),
                  candidate.activity().window(),
                  catalog.activity(),
                  candidate.phase(),
                  candidate.mode(),
                  new FeasibilityEvaluation(FeasibilityEvaluation.Status.FEASIBLE, reference));
          var next =
              previous.commit(
                  selected, new AssignmentId(UUID.randomUUID().toString()), previous.frozenUntil());
          var snapshot = next.snapshot();
          next =
              MissionSchedule.restore(
                  new MissionSchedule.Snapshot(
                      snapshot.key(),
                      snapshot.version(),
                      snapshot.frozenUntil(),
                      snapshot.activities(),
                      snapshot.assignments(),
                      new ResourceValidation(
                          ResourceValidation.Status.VALIDATED, reference, List.of())));
          schedules.commit(request.expectedScheduleVersion(), next);
          store.create(
              "planning-activity-catalog", candidate.activity().id().value(), run.catalog());
          run.operations()
              .ifPresent(
                  o ->
                      store.create(
                          "planning-activity-operation-profiles",
                          candidate.activity().id().value(),
                          o.resourceProfiles()));
          var result =
              new Committed(
                  "SIMULATION",
                  MODEL,
                  id,
                  run.requestRevision(),
                  request.candidateId(),
                  cameraId,
                  actor.getName(),
                  request.reviewReference(),
                  List.of(
                      "CONTINUOUS_EXPOSURE",
                      "PRECISION_ATTITUDE",
                      "PRECISION_ILLUMINATION",
                      "COMMAND_RELEASE_AND_GROUND_DISPATCH"),
                  resources,
                  next.snapshot());
          var saved = store.create("simulation-v1-schedule", assignmentKey, result);
          db.update(
              "UPDATE planning_work SET status='SIMULATION_COMMITTED',lease_token=NULL,"
                  + "lease_until=NULL WHERE request_id=?",
              run.requestId());
          store.event(
              "ScheduleAssignmentCommitted",
              run.requestId(),
              run.requestRevision(),
              UUID.randomUUID(),
              null,
              new RequestProgress(run.requestId(), run.requestRevision(), reference));
          store.event(
              "ScheduleVersionCommitted",
              json.fingerprint(scheduleKey),
              next.version(),
              UUID.randomUUID(),
              null,
              Map.of(
                  "environment",
                  "SIMULATION",
                  "evaluationModel",
                  MODEL,
                  "schedule",
                  next.snapshot(),
                  "runId",
                  id));
          return saved;
        });
  }

  @GetMapping({
    "/api/planning/simulation-schedules/{requestId}/{revision}",
    "/internal/planning/simulation-schedules/{requestId}/{revision}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Committed> read(
      @PathVariable String requestId, @PathVariable long revision) {
    return store.require("simulation-v1-schedule", requestId + ":" + revision, Committed.class);
  }
}
