package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.SimulationExecutionContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/** Bind received simulation evidence to the exact released load and simulator execution ledger. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
public class SimulationExecutionBindingApi {
  public record Bound(
      String environment,
      String status,
      String loadId,
      UUID scenarioId,
      List<String> requestIds,
      Observation observation,
      JsonNode ledger) {}

  private final StateStore store;
  private final Json json;
  private final ServiceHttp http;

  public SimulationExecutionBindingApi(StateStore store, Json json, ServiceHttp http) {
    this.store = store;
    this.json = json;
    this.http = http;
  }

  @PostMapping("/api/command-loads/{id}/simulation-execution/reconcile")
  public JsonNode reconcile(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    String scope = "simulation-execution-binding:" + actor.getName();
    var request = Map.of("loadId", id);
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var release =
        store
            .require(SimulationDispatchApi.RELEASE, id, SimulationDispatchApi.Release.class)
            .body();
    var delivery =
        store
            .require(SimulationDispatchApi.DELIVERY, id, SimulationDispatchApi.Delivery.class)
            .body();
    if (!"ACCEPTED".equals(delivery.status()))
      throw ApiException.conflict("Delivery has not been confirmed");
    var evidence =
        store
            .require(
                "simulation-execution-evidence",
                release.scenarioId() + ":" + id,
                ExecutionEvidenceApi.Evidence.class)
            .body();
    var observation = evidence.observation();
    var ledger =
        http.get(
            "simulator",
            "/internal/simulation/scenarios/"
                + release.scenarioId()
                + "/loads/"
                + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8),
            JsonNode.class);
    if (!release.scenarioId().equals(observation.scenarioId())
        || !id.equals(observation.loadId())
        || !release
            .prepared()
            .load()
            .scheduleKey()
            .spacecraftId()
            .value()
            .equals(observation.spacecraftId())
        || !id.equals(ledger.path("id").asText())
        || ledger.path("version").asLong() != observation.ledgerVersion()
        || !json.fingerprint(ledger).equals(observation.ledgerSha256())
        || !json.fingerprint(release.submission())
            .equals(ledger.path("body").path("submissionSha256").asText())
        || !json.fingerprint(release.submission().path("load"))
            .equals(json.fingerprint(ledger.path("body").path("load"))))
      throw ApiException.invalid("Execution evidence does not match the released simulator ledger");
    var actual = new ArrayList<CommandEvidence>();
    for (var entry : ledger.path("body").path("entries"))
      actual.add(
          new CommandEvidence(
              entry.path("command").path("id").path("value").asText(),
              ModeledOutcome.valueOf(entry.path("status").asText()),
              entry.path("completionTick").asLong(),
              entry.path("catalogSha256").asText()));
    if (!actual.equals(observation.commands()))
      throw ApiException.invalid("Observed commands differ from ledger effects");
    var requests =
        release.prepared().sources().schedule().assignments().stream()
            .map(a -> a.requestId().value())
            .distinct()
            .sorted()
            .toList();
    String status =
        actual.stream().allMatch(c -> c.outcome() == ModeledOutcome.EFFECT_APPLIED)
            ? "SIMULATION_EFFECTS_CONFIRMED"
            : "SIMULATION_EFFECTS_REJECTED";
    var result =
        new Bound("SIMULATION", status, id, release.scenarioId(), requests, observation, ledger);
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("simulation-bound-execution:" + id);
          var old = store.find("simulation-bound-execution", id, Bound.class);
          if (old.isPresent()) return old.get();
          var saved = store.create("simulation-bound-execution", id, result);
          store.event("SimulationExecutionBound", id, 1, UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  @GetMapping({
    "/api/command-loads/{id}/simulation-execution",
    "/internal/command-loads/{id}/simulation-execution"
  })
  public StateStore.State<Bound> read(@PathVariable String id) {
    return store.require("simulation-bound-execution", id, Bound.class);
  }
}
