package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Re-evaluates a pinned run against current owned schedules without reserving or committing it. */
@RestController
public class PlanningResourceReassessmentApi {
  public record Reassessment(
      String runId,
      String runSha256,
      MissionInstant evaluatedAt,
      PlanningResources.Assessment resources) {}

  private final StateStore store;
  private final Json json;
  private final Clock clock;
  private final JdbcScheduleRepository schedules;

  public PlanningResourceReassessmentApi(
      StateStore store, Json json, Clock clock, JdbcTemplate db) {
    this.store = store;
    this.json = json;
    this.clock = clock;
    schedules = new JdbcScheduleRepository(store, json, db);
  }

  @PostMapping({
    "/api/planning/runs/{id}/resources/reassess",
    "/internal/planning/runs/{id}/resources/reassess"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode reassess(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    String scope = "planning-resource-reassessment:" + actor.getName();
    var request = Map.of("runId", id);
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    var run = store.require("planning-run", id, PlanningRuns.Published.class).body();
    var attempt =
        store
            .require("planning-input-attempt", run.inputAttemptId(), PlanningInputs.Attempt.class)
            .body();
    var assets =
        attempt.assets().stream().filter(a -> a.spacecraftId().equals(run.spacecraftId())).toList();
    if (!run.id().equals(id) || assets.size() != 1)
      throw ApiException.invalid("Resource reassessment run/asset identity mismatch");
    var asset = assets.getFirst();
    var derived = new PlanningRuns(store, json).derive(attempt, asset);
    if (!json.fingerprint(derived).equals(json.fingerprint(run)))
      throw ApiException.invalid("Run does not match its immutable source attempt");
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          schedules.lockSpacecraft(new msc.domain.shared.Ids.SpacecraftId(run.spacecraftId()));
          var evaluatedAt = clock.now();
          var resources =
              new PlanningResources(json)
                  .capture(store, schedules, attempt, asset, run, evaluatedAt);
          String resultId = UUID.randomUUID().toString();
          var result = new Reassessment(id, json.fingerprint(run), evaluatedAt, resources);
          var saved = store.create("planning-resource-reassessment", resultId, result);
          store.event(
              "PlanningResourcesReassessed",
              resultId,
              saved.version(),
              UUID.randomUUID(),
              null,
              Map.of("runId", id, "reassessmentId", resultId, "runSha256", result.runSha256()));
          return saved;
        });
  }

  @GetMapping({
    "/api/planning/resource-reassessments/{id}",
    "/internal/planning/resource-reassessments/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Reassessment> result(@PathVariable String id) {
    return store.require("planning-resource-reassessment", id, Reassessment.class);
  }
}
