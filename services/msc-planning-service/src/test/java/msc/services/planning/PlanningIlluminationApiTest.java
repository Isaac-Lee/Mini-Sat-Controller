package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import msc.contracts.IlluminationContracts.Aoi;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.contracts.SolarIntervalContracts.Assumptions;
import msc.domain.time.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class PlanningIlluminationApiTest {
  final PlanningRunsTest fixtures = new PlanningRunsTest();
  final Json json = fixtures.json;
  final ServiceHttp http = mock(ServiceHttp.class);
  final StateStore store = mock(StateStore.class);
  final PlanningIlluminationApi api = new PlanningIlluminationApi(store, http, json);

  PlanningRuns.Published run() {
    var asset = fixtures.asset();
    var original = new PlanningRuns(store, json).derive(fixtures.attempt("request", 1, asset), asset);
    var value = original.geometry().value().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) value).putObject("prediction").putObject("body").put("referenceDigest", "a".repeat(64));
    var geometry = new PlanningInputs.Evidence("flight-dynamics", "/test", json.fingerprint(value), value);
    return new PlanningRuns.Published(original.id(), original.requestId(), original.requestRevision(),
        original.inputAttemptId(), original.spacecraftId(), original.sourceHashes(), original.catalog(),
        original.simulationModel(), geometry, original.run(), original.operations());
  }

  JsonNode source(PlanningRuns.Published run, long version) {
    var model = json.convert(run.simulationModel().value().get("body"), Model.class);
    var a = new Assumptions(run.spacecraftId(), model.missionDefinitionVersion(), "SIMULATION", "solar-model",
        "a".repeat(64), new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
        new Aoi("extent", 120, 130, 30, 40, 123), .001, .0001, 5, "unit fixture");
    return json.tree(new StateStore.State<>(run.spacecraftId(), version, a));
  }

  @Test void derivesCandidateQueryAndPreservesConditionalOwnerEvidence() {
    var run = run(); var source = source(run, 2);
    when(http.get(eq("mission-definition"), anyString(), eq(JsonNode.class))).thenReturn(source);
    when(http.post(eq("flight-dynamics"), eq("/internal/solar-intervals"), any(), anyString(), eq(JsonNode.class)))
        .thenAnswer(invocation -> {
          var query = json.tree(invocation.getArgument(2));
          assertEquals(123, query.path("query").path("aoi").path("altitudeMeters").asDouble());
          assertEquals(1100, query.path("query").path("horizon").path("start").path("seconds").asLong());
          return json.tree(Map.of("id", "fd-result", "version", 1, "body", Map.of(
              "request", query, "assumptions", source, "assumptionsSha256", json.fingerprint(source),
              "outcome", "SUPPORTED_BY_DECLARED_ASSUMPTIONS")));
        });
    var result = api.compute(run, 2);
    assertEquals(json.fingerprint(run), result.runSha256());
    assertEquals(run.run().candidates().getFirst().id().value(), result.candidates().getFirst().candidateId());
    assertEquals(msc.domain.planning.FeasibilityEvaluation.Status.NOT_EVALUATED,
        run.run().candidates().getFirst().feasibility().status());
    verifyNoInteractions(store);
  }

  @Test void wrongOwnerVersionAndMismatchedQueryCannotBecomeAssessment() {
    var run = run();
    when(http.get(eq("mission-definition"), anyString(), eq(JsonNode.class))).thenReturn(source(run, 3));
    assertThrows(ApiException.class, () -> api.compute(run, 2));
    verify(http, never()).post(anyString(), anyString(), any(), anyString(), any());
    var source = source(run, 2);
    when(http.get(eq("mission-definition"), anyString(), eq(JsonNode.class))).thenReturn(source);
    when(http.post(eq("flight-dynamics"), anyString(), any(), anyString(), eq(JsonNode.class)))
        .thenReturn(json.tree(Map.of("id", "wrong", "version", 1, "body", Map.of(
            "request", Map.of("spacecraftId", "other"), "assumptions", source,
            "assumptionsSha256", json.fingerprint(source), "outcome", "SUPPORTED_BY_DECLARED_ASSUMPTIONS"))));
    assertThrows(ApiException.class, () -> api.compute(run, 2));
    verifyNoInteractions(store);
  }
}
