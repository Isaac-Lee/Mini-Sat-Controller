package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import msc.contracts.PointingContracts.*;
import msc.contracts.SimulationCameraModelContracts;
import msc.contracts.SimulationPlanningContracts;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class PlanningCameraApiTest {
  final PlanningRunsTest fixtures = new PlanningRunsTest();
  final Json json = fixtures.json;
  final ServiceHttp http = mock(ServiceHttp.class);
  final StateStore store = mock(StateStore.class);
  final PlanningCameraApi api = new PlanningCameraApi(store, http, json);
  boolean tamper;
  PlanningInputs.Attempt attempt;

  PlanningRuns.Published run() {
    var asset = fixtures.asset();
    var original =
        new PlanningRuns(store, json).derive(fixtures.attempt("request", 1, asset), asset);
    var value = (ObjectNode) original.geometry().value().deepCopy();
    var area = value.path("area");
    var target =
        new Target(
            "center",
            (area.path("south").asDouble() + area.path("north").asDouble()) / 2,
            (area.path("west").asDouble() + area.path("east").asDouble()) / 2,
            0);
    value.set(
        "prediction",
        json.tree(
            Map.of(
                "body",
                Map.of(
                    "solutionId",
                    "orbit",
                    "spacecraftId",
                    original.spacecraftId(),
                    "referenceDigest",
                    "digest",
                    "query",
                    Map.of("target", target)))));
    var geometry =
        new PlanningInputs.Evidence("flight-dynamics", "/test", json.fingerprint(value), value);
    var boundAsset =
        new PlanningInputs.Asset(
            asset.spacecraftId(),
            asset.inputs(),
            asset.missing(),
            asset.catalog(),
            Optional.of(geometry),
            asset.simulationModel(),
            asset.activityOptions());
    attempt = fixtures.attempt("request", 1, boundAsset);
    return new PlanningRuns(store, json).derive(attempt, boundAsset);
  }

  JsonNode source(PlanningRuns.Published run, long planningVersion) {
    var model =
        json.convert(
            run.simulationModel().value().get("body"), SimulationPlanningContracts.Model.class);
    return json.tree(
        new StateStore.State<>(
            run.spacecraftId(),
            1,
            new SimulationCameraModelContracts.Model(
                run.spacecraftId(),
                model.missionDefinitionVersion(),
                "SIMULATION",
                SimulationCameraModelContracts.PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                1,
                1,
                100,
                100,
                5,
                30,
                0,
                planningVersion,
                "test",
                "synthetic")));
  }

  void stub(PlanningRuns.Published run, JsonNode source) {
    when(http.get(eq("mission-definition"), anyString(), eq(JsonNode.class))).thenReturn(source);
    when(http.post(
            eq("flight-dynamics"),
            eq("/internal/required-target-pointing"),
            any(),
            anyString(),
            eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              var body = json.tree(inv.getArgument(2));
              var q = json.convert(body.get("query"), RequiredTargetPointingQuery.class);
              assertEquals(run.run().candidates().getFirst().activity().window(), q.horizon());
              assertEquals(1, q.stepSeconds());
              var sample =
                  new RequiredTargetPointingSample(
                      q.horizon().start(),
                      new Trajectory.Vector(7000000, 0, 0),
                      new Trajectory.Vector(1, 0, 0),
                      new Trajectory.Vector(1, 0, 0),
                      0,
                      500000,
                      new Trajectory.GroundPoint(q.horizon().start(), 0, 0, 500000, "test"),
                      q.target(),
                      90,
                      0,
                      true);
              return json.tree(
                  new StateStore.State<>(
                      "pointing",
                      1,
                      new RequiredTargetPointingResult(
                          "orbit",
                          run.spacecraftId(),
                          "propagation",
                          "pointing",
                          "orbit-hash",
                          "digest",
                          q,
                          List.of(sample),
                          RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE)));
            });
    when(http.post(
            eq("flight-dynamics"),
            eq("/internal/camera-footprint-evaluations"),
            any(),
            anyString(),
            eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              var body = json.tree(inv.getArgument(2));
              var m =
                  json.tree(
                      Map.ofEntries(
                          Map.entry("id", "camera"),
                          Map.entry("query", body.get("query")),
                          Map.entry("spacecraftId", run.spacecraftId()),
                          Map.entry("solutionId", "orbit"),
                          Map.entry("orbitSourceHash", "orbit-hash"),
                          Map.entry("orbitPropagationModel", "propagation"),
                          Map.entry("referenceDigest", "digest"),
                          Map.entry("cameraModelHash", json.fingerprint(source)),
                          Map.entry("planningModelHash", run.simulationModel().sha256()),
                          Map.entry("sampleCount", 1),
                          Map.entry("scope", "SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE"),
                          Map.entry("objectReference", "s3://msc-flight-dynamics/object")));
              if (tamper) ((ObjectNode) m.path("query").path("area")).put("east", 140);
              return json.tree(new StateStore.State<>("camera", 1, m));
            });
  }

  @Test
  void derivesOwnedCandidateRequestsAndBindsCameraToRunPlanningVersion() {
    var run = run();
    var source = source(run, 2);
    stub(run, source);
    var result = api.compute(run, 1);
    assertEquals(json.fingerprint(run), result.runSha256());
    assertEquals(source, result.cameraModel());
    assertEquals(
        run.run().candidates().getFirst().id().value(),
        result.candidates().getFirst().candidateId());
    assertEquals("SAMPLED_CAMERA_EVIDENCE_EXPOSURE_AND_ATTITUDE_PENDING", result.scope());
    assertEquals(
        msc.domain.planning.FeasibilityEvaluation.Status.NOT_EVALUATED,
        run.run().candidates().getFirst().feasibility().status());
  }

  @Test
  void wrongPlanningPinAndSubstitutedAoiCannotBePublished() {
    var run = run();
    stub(run, source(run, 3));
    assertThrows(ApiException.class, () -> api.compute(run, 1));
    verify(http, never()).post(anyString(), anyString(), any(), anyString(), any());
    stub(run, source(run, 2));
    tamper = true;
    assertThrows(ApiException.class, () -> api.compute(run, 1));
    verifyNoInteractions(store);
  }
}
