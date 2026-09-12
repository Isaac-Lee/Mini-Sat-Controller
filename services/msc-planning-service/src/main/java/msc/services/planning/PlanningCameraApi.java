package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationQuery;
import msc.contracts.PointingContracts.RequiredTargetPointingQuery;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.SimulationCameraModelContracts;
import msc.contracts.SimulationPlanningContracts;
import msc.contracts.TaskingContracts.Area;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Binds sampled FD camera evidence to immutable Planning candidates; never approves exposure. */
@RestController
public class PlanningCameraApi {
  public record Evaluate(long cameraModelVersion) {
    public Evaluate {
      if (cameraModelVersion < 1)
        throw new IllegalArgumentException("Exact camera version required");
    }
  }

  public record CandidateResult(
      String candidateId, String pointingResultId, JsonNode cameraResult) {}

  public record Assessment(
      String runId,
      String runSha256,
      JsonNode cameraModel,
      List<CandidateResult> candidates,
      String scope) {
    public Assessment {
      candidates = List.copyOf(candidates);
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public PlanningCameraApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  @PostMapping({"/api/planning/runs/{id}/camera", "/internal/planning/runs/{id}/camera"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode evaluate(
      @PathVariable String id,
      @RequestBody Evaluate request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return evaluateForActor(id, request, key, actor.getName());
  }

  JsonNode evaluateForActor(String id, Evaluate request, String key, String actor) {
    String scope = "planning-camera:" + id + ":" + actor;
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
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
      throw ApiException.invalid("Camera assessment run differs from its owned input attempt");
    var assessment = compute(run, request.cameraModelVersion());
    String identity = json.fingerprint(List.of(id, request.cameraModelVersion()));
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("planning-camera:" + identity);
          var existing = store.find("planning-camera", identity, Assessment.class);
          if (existing.isPresent()) {
            if (!json.fingerprint(existing.get().body()).equals(json.fingerprint(assessment)))
              throw ApiException.conflict("Immutable camera assessment changed");
            return existing.get();
          }
          var saved = store.create("planning-camera", identity, assessment);
          store.event(
              "PlanningCameraEvaluated",
              run.requestId(),
              run.requestRevision(),
              UUID.randomUUID(),
              null,
              Map.of(
                  "runId",
                  id,
                  "assessmentId",
                  identity,
                  "cameraModelVersion",
                  request.cameraModelVersion()));
          return saved;
        });
  }

  Assessment compute(PlanningRuns.Published run, long version) {
    if (run.run().candidates().isEmpty() || run.run().candidates().size() > 32)
      throw ApiException.invalid("Camera assessment requires 1..32 candidates");
    for (var e : List.of(run.geometry(), run.simulationModel()))
      if (!json.fingerprint(e.value()).equals(e.sha256()))
        throw ApiException.invalid("Captured camera input hash mismatch");
    var model =
        json.convert(
            run.simulationModel().value().get("body"), SimulationPlanningContracts.Model.class);
    var prediction = run.geometry().value().path("prediction").path("body");
    String solution = prediction.path("solutionId").asText();
    String digest = prediction.path("referenceDigest").asText();
    if (!run.spacecraftId().equals(model.spacecraftId())
        || solution.isBlank()
        || digest.isBlank()
        || !run.spacecraftId().equals(prediction.path("spacecraftId").asText()))
      throw ApiException.invalid("Camera run orbit/reference binding missing");
    var target = json.convert(prediction.path("query").get("target"), Target.class);
    var area = json.convert(run.geometry().value().get("area"), Area.class);
    var source =
        http.get(
            "mission-definition",
            "/internal/simulation-camera-models/"
                + PlanningInputs.segment(run.spacecraftId())
                + "/versions/"
                + version,
            JsonNode.class);
    requireEnvelope(source, run.spacecraftId(), version);
    var camera = json.convert(source.get("body"), SimulationCameraModelContracts.Model.class);
    if (!run.spacecraftId().equals(camera.spacecraftId())
        || !model.missionDefinitionVersion().equals(camera.missionDefinitionVersion())
        || camera.simulationPlanningModelVersion()
            != run.simulationModel().value().path("version").asLong())
      throw ApiException.invalid("Camera model does not pin this run's Planning model");
    var results = new ArrayList<CandidateResult>();
    for (var candidate : run.run().candidates()) {
      var window = candidate.activity().window();
      if (window
              .end()
              .compareTo(
                  window.start().plus(new msc.domain.time.MissionDuration(2_000_000_000_000L)))
          > 0)
        throw ApiException.invalid(
            "Camera candidate duration exceeds the 2000-sample evaluation limit");
      var query = new RequiredTargetPointingQuery(target, candidate.activity().window(), 1, 0);
      var body = Map.of("solutionId", solution, "query", query);
      var pointing =
          http.post(
              "flight-dynamics",
              "/internal/required-target-pointing",
              body,
              "planning-camera-pointing:" + json.fingerprint(body),
              JsonNode.class);
      requireEnvelope(pointing, pointing.path("id").asText(), 1);
      var profile = json.convert(pointing.get("body"), RequiredTargetPointingResult.class);
      if (!solution.equals(profile.solutionId())
          || !run.spacecraftId().equals(profile.spacecraftId())
          || !digest.equals(profile.referenceDigest())
          || !query.equals(profile.query()))
        throw ApiException.invalid("FD pointing result differs from candidate inputs");
      var cameraQuery =
          new CameraFootprintEvaluationQuery(pointing.path("id").asText(), version, area);
      var cameraBody = Map.of("query", cameraQuery);
      var result =
          http.post(
              "flight-dynamics",
              "/internal/camera-footprint-evaluations",
              cameraBody,
              "planning-camera-footprint:" + json.fingerprint(cameraBody),
              JsonNode.class);
      requireEnvelope(result, result.path("id").asText(), 1);
      var m = result.path("body");
      if (!result.path("id").asText().equals(m.path("id").asText())
          || !json.fingerprint(cameraQuery).equals(json.fingerprint(m.get("query")))
          || !run.spacecraftId().equals(m.path("spacecraftId").asText())
          || !solution.equals(m.path("solutionId").asText())
          || !profile.orbitSourceHash().equals(m.path("orbitSourceHash").asText())
          || !profile.orbitPropagationModel().equals(m.path("orbitPropagationModel").asText())
          || !digest.equals(m.path("referenceDigest").asText())
          || !json.fingerprint(source).equals(m.path("cameraModelHash").asText())
          || !run.simulationModel().sha256().equals(m.path("planningModelHash").asText())
          || m.path("sampleCount").asInt() != profile.samples().size()
          || !"SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE".equals(m.path("scope").asText())
          || m.path("objectReference").asText().isBlank())
        throw ApiException.invalid("FD camera result differs from pinned run inputs");
      results.add(
          new CandidateResult(candidate.id().value(), pointing.path("id").asText(), result));
    }
    return new Assessment(
        run.id(),
        json.fingerprint(run),
        source,
        results,
        "SAMPLED_CAMERA_EVIDENCE_EXPOSURE_AND_ATTITUDE_PENDING");
  }

  private void requireEnvelope(JsonNode source, String id, long version) {
    if (id.isBlank()
        || !id.equals(source.path("id").asText())
        || !source.path("version").isIntegralNumber()
        || !source.path("version").canConvertToLong()
        || source.path("version").asLong() != version)
      throw ApiException.invalid("Camera evidence owner identity/version mismatch");
  }

  @GetMapping({
    "/api/planning/runs/{id}/camera/{version}",
    "/internal/planning/runs/{id}/camera/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Assessment> read(@PathVariable String id, @PathVariable long version) {
    return store.require(
        "planning-camera", json.fingerprint(List.of(id, version)), Assessment.class);
  }
}
