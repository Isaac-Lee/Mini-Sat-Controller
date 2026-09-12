package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.contracts.TaskingContracts.Area;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.time.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.Evidence;
import org.junit.jupiter.api.Test;

class PlanningGeometryTest {
  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final Area area = new Area("aoi", 127, 36, 127.1, 36.1, "test");
  final ServiceHttp http = mock(ServiceHttp.class);
  final StateStore store = mock(StateStore.class);
  final PlanningGeometry geometry = new PlanningGeometry(http, json, store);

  Evidence evidence(Object body) {
    return new Evidence("owner", "/test", json.fingerprint(body), json.tree(body));
  }

  Evidence catalog() {
    return evidence(
        json.read(
            """
            {"id":"catalog","version":1,"activity":{"id":{"value":"imaging"},"version":1,
            "name":"IMAGE","approved":true,"exclusiveResources":[],"allowedPhases":["ROUTINE"],
            "allowedModes":["NOMINAL"],"riskClass":"LOW","commandTemplateReference":"image:1"},
            "template":{"id":"image","version":1,"operation":"IMAGE","parameters":{}},
            "resources":{"powerWatts":1,"generatedMegabytes":1,"propellantKilograms":0},
            "authority":"AUTO_ALLOWED","durationSeconds":10,"approvalReference":"sim-test"}
            """,
            JsonNode.class));
  }

  Map<Input, Evidence> inputs(String kind) {
    return Map.of(
        Input.ORBIT,
            evidence(
                Map.of(
                    "designation",
                    Map.of("value", Map.of("orbitSolutionId", Map.of("value", "orbit"))),
                    "solution",
                    Map.of("value", Map.of("kind", kind)))),
        Input.AGILITY,
            evidence(
                Map.of(
                    "version",
                    1,
                    "body",
                    Map.of(
                        "spacecraftId",
                        "norad-63229",
                        "missionDefinitionVersion",
                        "v1",
                        "environment",
                        "SIMULATION",
                        "maximumOffNadirDegrees",
                        30,
                        "slewRateDegreesPerSecond",
                        1,
                        "settlingSeconds",
                        0,
                        "approvalReference",
                        "sim-test"))),
        Input.EOP, evidence(Map.of("digest", "pinned-reference")));
  }

  void respond(String craft) {
    when(http.post(eq("flight-dynamics"), anyString(), any(), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            call -> {
              Object body = call.getArgument(2);
              var query =
                  body instanceof AccessPrediction.Query q
                      ? q
                      : (AccessPrediction.Query) ((Map<?, ?>) body).get("query");
              return json.tree(
                  Map.of(
                      "id",
                      "prediction",
                      "version",
                      1,
                      "body",
                      new AccessPrediction(
                          "orbit",
                          craft,
                          "model",
                          "pinned-reference",
                          query,
                          .001,
                          10,
                          List.of(
                              new TimeWindow(
                                  query.horizon().start(),
                                  query
                                      .horizon()
                                      .start()
                                      .plus(new MissionDuration(10_000_000_000L)))))));
            });
  }

  @Test
  void sameBucketUsesPublishedEvidenceAndNextBucketSearchesAgain() {
    respond("norad-63229");
    var first =
        geometry.search(
            "norad-63229",
            area,
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
            inputs("CARTESIAN"),
            catalog());
    String key = first.value().path("searchKey").asText();
    when(store.find("planning-geometry", key, Evidence.class))
        .thenReturn(Optional.of(new StateStore.State<>(key, 1, first)));
    var replay =
        geometry.search(
            "norad-63229",
            area,
            new TimeWindow(MissionInstant.tai(1050), MissionInstant.tai(2000)),
            inputs("CARTESIAN"),
            catalog(),
            Optional.empty(),
            new PlanningGeometry.Budget(0));
    assertEquals(first, replay);
    verify(http, times(1)).post(anyString(), anyString(), any(), anyString(), eq(JsonNode.class));
    var renewed =
        geometry.search(
            "norad-63229",
            area,
            new TimeWindow(MissionInstant.tai(1201), MissionInstant.tai(2000)),
            inputs("CARTESIAN"),
            catalog());
    assertNotEquals(key, renewed.value().path("searchKey").asText());
    verify(http, times(2)).post(anyString(), anyString(), any(), anyString(), eq(JsonNode.class));
  }

  @Test
  void failedFreshLookupConsumesBudgetAndCannotCauseUnboundedRetries() {
    when(http.post(anyString(), anyString(), any(), anyString(), eq(JsonNode.class)))
        .thenThrow(new IllegalStateException("owner unavailable"));
    var budget = new PlanningGeometry.Budget(1);
    var horizon = new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000));
    assertThrows(
        IllegalStateException.class,
        () ->
            geometry.search(
                "norad-63229",
                area,
                horizon,
                inputs("CARTESIAN"),
                catalog(),
                Optional.empty(),
                budget));
    assertThrows(
        PlanningGeometry.BudgetReached.class,
        () ->
            geometry.search(
                "norad-63229",
                area,
                horizon,
                inputs("CARTESIAN"),
                catalog(),
                Optional.empty(),
                budget));
    verify(http, times(1)).post(anyString(), anyString(), any(), anyString(), eq(JsonNode.class));
  }

  @Test
  void publicGpUsesPinnedEndpointAndRejectsWrongSpacecraft() {
    respond("wrong-craft");
    assertThrows(
        ApiException.class,
        () ->
            geometry.search(
                "norad-63229",
                area,
                new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
                inputs("PUBLIC_GP"),
                catalog()));
    verify(http)
        .post(
            eq("flight-dynamics"),
            eq("/internal/public-orbits/orbit/access-predictions"),
            any(AccessPrediction.Query.class),
            anyString(),
            eq(JsonNode.class));
    verify(store, never()).create(anyString(), anyString(), any());
  }
}
