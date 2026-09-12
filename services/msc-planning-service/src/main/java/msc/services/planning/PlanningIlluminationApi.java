package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.IlluminationContracts.*;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.contracts.SolarIntervalContracts.Assumptions;
import msc.contracts.TaskingContracts.Area;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Records owner-computed illumination for immutable candidate windows, not schedule commitment. */
@RestController
public class PlanningIlluminationApi {
  public record Evaluate(long assumptionsVersion) {
    public Evaluate {
      if (assumptionsVersion <= 0)
        throw new IllegalArgumentException("Exact assumptions version required");
    }
  }

  public record CandidateResult(String candidateId, JsonNode ownerResult) {}

  public record Assessment(
      String runId, String runSha256, long assumptionsVersion, List<CandidateResult> candidates) {
    public Assessment {
      candidates = List.copyOf(candidates);
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public PlanningIlluminationApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class)
  @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
  public ApiErrors.Error missingOwner() {
    return new ApiErrors.Error("NOT_FOUND", "Required illumination artifact not found");
  }

  @PostMapping("/api/planning/runs/{id}/illumination")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode evaluate(
      @PathVariable String id,
      @RequestBody Evaluate request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return evaluateForActor(id, request, key, actor.getName());
  }

  JsonNode evaluateForActor(String id, Evaluate request, String key, String actor) {
    String scope = "planning-illumination:" + id + ":" + actor;
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var run = store.require("planning-run", id, PlanningRuns.Published.class).body();
    var assessment = compute(run, request.assumptionsVersion());
    String identity = json.fingerprint(List.of(id, request.assumptionsVersion()));
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("planning-illumination:" + identity);
          var existing = store.find("planning-illumination", identity, Assessment.class);
          if (existing.isPresent()) {
            if (!json.fingerprint(existing.get().body()).equals(json.fingerprint(assessment)))
              throw ApiException.conflict("Immutable illumination result changed");
            return existing.get();
          }
          var saved = store.create("planning-illumination", identity, assessment);
          store.event(
              "PlanningIlluminationEvaluated",
              run.requestId(),
              run.requestRevision(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  Assessment compute(PlanningRuns.Published run, long version) {
    if (run.run().candidates().isEmpty() || run.run().candidates().size() > 32)
      throw ApiException.invalid("Illumination evaluation requires 1..32 candidates");
    for (var evidence : List.of(run.geometry(), run.simulationModel()))
      if (!json.fingerprint(evidence.value()).equals(evidence.sha256()))
        throw ApiException.invalid("Captured illumination input hash mismatch");
    var model = json.convert(run.simulationModel().value().get("body"), Model.class);
    var area = json.convert(run.geometry().value().get("area"), Area.class);
    String digest =
        run.geometry().value().path("prediction").path("body").path("referenceDigest").asText();
    if (!run.spacecraftId().equals(model.spacecraftId()) || digest.isBlank())
      throw ApiException.invalid("Run mission/reference binding missing");
    var source =
        http.get(
            "mission-definition",
            "/internal/solar-interval-assumptions/"
                + PlanningInputs.segment(run.spacecraftId())
                + "/versions/"
                + version,
            JsonNode.class);
    if (!run.spacecraftId().equals(source.path("id").asText())
        || !source.path("version").isIntegralNumber()
        || !source.path("version").canConvertToLong()
        || source.path("version").asLong() != version)
      throw ApiException.invalid("Assumptions owner identity/version mismatch");
    var assumptions = json.convert(source.get("body"), Assumptions.class);
    if (!run.spacecraftId().equals(assumptions.spacecraftId())
        || !model.missionDefinitionVersion().equals(assumptions.missionDefinitionVersion()))
      throw ApiException.invalid("Illumination assumptions mission mismatch");
    var target =
        new Aoi(
            area.id(),
            area.west(),
            area.east(),
            area.south(),
            area.north(),
            assumptions.extent().altitudeMeters());
    var results = new ArrayList<CandidateResult>();
    for (var candidate : run.run().candidates()) {
      var query =
          new TargetIlluminationQuery(
              target, candidate.activity().window(), model.minimumSunElevationDegrees());
      assumptions.requireCoverage(assumptions.solarModel(), digest, target, query.horizon());
      var body =
          Map.of("spacecraftId", run.spacecraftId(), "assumptionsVersion", version, "query", query);
      var result =
          http.post(
              "flight-dynamics",
              "/internal/solar-intervals",
              body,
              "planning-solar-" + json.fingerprint(body),
              JsonNode.class);
      var value = result.path("body");
      if (result.path("id").asText().isBlank()
          || result.path("version").asLong() < 1
          || !json.fingerprint(body).equals(json.fingerprint(value.get("request")))
          || !json.fingerprint(source).equals(json.fingerprint(value.get("assumptions")))
          || !json.fingerprint(source).equals(value.path("assumptionsSha256").asText())
          || !Set.of("SUPPORTED_BY_DECLARED_ASSUMPTIONS", "NOT_ESTABLISHED")
              .contains(value.path("outcome").asText()))
        throw ApiException.invalid("FD interval result source/query binding mismatch");
      results.add(new CandidateResult(candidate.id().value(), result));
    }
    return new Assessment(run.id(), json.fingerprint(run), version, results);
  }

  @GetMapping({
    "/api/planning/runs/{id}/illumination/{version}",
    "/internal/planning/runs/{id}/illumination/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Assessment> read(@PathVariable String id, @PathVariable long version) {
    return store.require(
        "planning-illumination", json.fingerprint(List.of(id, version)), Assessment.class);
  }
}
