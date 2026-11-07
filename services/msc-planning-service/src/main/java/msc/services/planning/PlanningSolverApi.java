package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.domain.planning.*;
import msc.domain.shared.Ids.RequestId;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Greedy multi-spacecraft proposals only; the operator still commits via simulation-commit. */
@RestController
public class PlanningSolverApi {
  public record Solved(
      String environment,
      String scope,
      String runId,
      String inputAttemptId,
      String requestId,
      long requestRevision,
      List<String> consideredRunIds,
      List<MissionSchedule.Snapshot> committedContext,
      PriorityGreedySolver.Result result,
      String commitRoute) {}

  private final StateStore store;
  private final Json json;
  private final Clock clock;
  private final JdbcTemplate db;
  private final JdbcScheduleRepository schedules;

  public PlanningSolverApi(StateStore store, Json json, Clock clock, JdbcTemplate db) {
    this.store = store;
    this.json = json;
    this.clock = clock;
    this.db = db;
    schedules = new JdbcScheduleRepository(store, json, db);
  }

  @PostMapping("/api/planning/runs/{id}/solve")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public JsonNode solve(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    String scope = "planning-solve:" + actor.getName();
    var body = Map.of("runId", id);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var run = store.require("planning-run", id, PlanningRuns.Published.class).body();
    var attempt =
        store
            .require("planning-input-attempt", run.inputAttemptId(), PlanningInputs.Attempt.class)
            .body();
    var priority =
        attempt
            .request()
            .map(e -> e.value().path("request").path("requestedPriority"))
            .filter(JsonNode::canConvertToInt)
            .orElseThrow(() -> ApiException.invalid("Captured request priority missing"))
            .asInt();
    // Sibling runs of the same attempt are the same request on other spacecraft.
    var siblings = new ArrayList<PlanningRuns.Published>();
    for (var runId :
        store.require("planning-run-index", attempt.id(), PlanningRuns.Index.class).body().runIds())
      siblings.add(store.require("planning-run", runId, PlanningRuns.Published.class).body());
    if (siblings.stream().noneMatch(s -> s.id().equals(id)))
      throw ApiException.invalid("Run is not indexed by its input attempt");
    // Lock spacecraft in sorted order, matching PlanningResources.capture's contract.
    siblings.sort(Comparator.comparing(PlanningRuns.Published::spacecraftId));
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          var work =
              db.queryForList(
                  "SELECT revision,last_attempt_id,invalidated_through FROM planning_work"
                      + " WHERE request_id=? FOR UPDATE",
                  run.requestId());
          if (work.size() != 1
              || ((Number) work.getFirst().get("revision")).longValue() != run.requestRevision()
              || ((Number) work.getFirst().get("invalidated_through")).longValue()
                  >= run.requestRevision()
              || !run.inputAttemptId().equals(work.getFirst().get("last_attempt_id")))
            throw ApiException.conflict("Planning input attempt was superseded");
          if (store
              .find(
                  "simulation-v1-schedule",
                  run.requestId() + ":" + run.requestRevision(),
                  SimulationScheduleApi.Committed.class)
              .isPresent())
            throw ApiException.conflict("Request revision already has a simulation schedule");
          var now = clock.now();
          var options = new ArrayList<PriorityGreedySolver.Option>();
          var heads = new TreeMap<String, MissionSchedule.Snapshot>();
          for (var sibling : siblings) {
            var asset =
                attempt.assets().stream()
                    .filter(a -> a.spacecraftId().equals(sibling.spacecraftId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.invalid("Run asset missing from attempt"));
            var assessment =
                new PlanningResources(json).capture(store, schedules, attempt, asset, sibling, now);
            assessment.scheduleHeads().forEach(h -> heads.put(json.fingerprint(h), h));
            var definition = json.convert(sibling.catalog().value(), CatalogEntry.class).activity();
            PlanningRun domainRun;
            try {
              domainRun = PlanningRun.restore(sibling.run(), (ignored, version) -> definition);
            } catch (IllegalArgumentException invalid) {
              throw ApiException.invalid("Stored run rejected: " + invalid.getMessage());
            }
            for (var candidate : domainRun.candidates()) {
              var forecast =
                  assessment.candidates().stream()
                      .filter(c -> c.candidateId().equals(candidate.id().value()))
                      .findFirst()
                      .flatMap(PlanningResources.CandidateResult::forecast);
              // Missing forecast evidence is an unevaluated option, never an implicit pass.
              options.add(
                  new PriorityGreedySolver.Option(
                      candidate,
                      forecast.orElseGet(
                          () ->
                              new ResourceTimeline.Result(
                                  ResourceValidation.Status.NOT_EVALUATED,
                                  "planning-solve",
                                  "planning-solve",
                                  Set.of(),
                                  List.of(),
                                  List.of()))));
            }
          }
          PriorityGreedySolver.Result result;
          try {
            var committed = heads.values().stream().map(MissionSchedule::restore).toList();
            result =
                PriorityGreedySolver.solve(
                    List.of(
                        new PriorityGreedySolver.Demand(
                            new RequestId(run.requestId()), priority, options)),
                    committed);
          } catch (IllegalArgumentException invalid) {
            throw ApiException.invalid("Stored planning data rejected: " + invalid.getMessage());
          }
          var solved =
              new Solved(
                  "SIMULATION",
                  PriorityGreedySolver.SCOPE,
                  id,
                  attempt.id(),
                  run.requestId(),
                  run.requestRevision(),
                  siblings.stream().map(PlanningRuns.Published::id).toList(),
                  List.copyOf(heads.values()),
                  result,
                  "POST /api/planning/runs/{runId}/simulation-commit");
          return store.create("planning-solve", json.fingerprint(List.of(scope, key)), solved);
        });
  }
}
