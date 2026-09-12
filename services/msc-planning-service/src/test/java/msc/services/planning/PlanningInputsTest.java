package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.ObservationRequest;
import msc.domain.time.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class PlanningInputsTest {
  Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  MissionInstant now = new MissionInstant(1000, 0, TimeScale.TAI);

  @Test
  void changedOrTerminalRequestNeverProceedsToFleetLookups() {
    String id = UUID.randomUUID().toString();
    var request =
        new AcceptedRequest(
            id,
            1,
            new Area("area", 127, 36, 127.1, 36.1, "test"),
            new Criteria(1, 1),
            Optional.empty(),
            10);
    var claim = new PlanningIntake.Claim(id, 1, UUID.randomUUID(), 0, request);
    var http = mock(ServiceHttp.class);
    var changed =
        new RequestDetails(
            new ObservationRequest(
                new RequestId(id),
                Optional.of(new AoiId("area")),
                2,
                "test",
                Optional.empty(),
                10,
                ObservationRequest.InteractionPreference.AUTO,
                ObservationRequest.Status.CANCELLED),
            "owner",
            "target",
            Optional.of(request.area()),
            request.criteria(),
            now,
            now,
            "cancelled",
            List.of());
    when(http.get("tasking", "/internal/requests/" + id, RequestDetails.class)).thenReturn(changed);
    var attempt = new PlanningInputs(http, json, () -> now, mock(StateStore.class)).collect(claim);
    assertEquals("INVALIDATED", attempt.status());
    verify(http, times(1)).get(anyString(), anyString(), any());
  }

  @Test
  void absentMissionInputsRemainWaitingWithSourceEvidenceNotFakeFeasibility() {
    String id = UUID.randomUUID().toString();
    var request =
        new AcceptedRequest(
            id,
            1,
            new Area("area", 127, 36, 127.1, 36.1, "test"),
            new Criteria(1, 1),
            Optional.empty(),
            10);
    var claim = new PlanningIntake.Claim(id, 1, UUID.randomUUID(), 0, request);
    var http = mock(ServiceHttp.class);
    var live =
        new RequestDetails(
            new ObservationRequest(
                new RequestId(id),
                Optional.of(new AoiId("area")),
                1,
                "test",
                Optional.empty(),
                10,
                ObservationRequest.InteractionPreference.AUTO,
                ObservationRequest.Status.ACCEPTED),
            "owner",
            "target",
            Optional.of(request.area()),
            request.criteria(),
            now,
            now,
            "accepted",
            List.of());
    when(http.get("tasking", "/internal/requests/" + id, RequestDetails.class)).thenReturn(live);
    when(http.get(
            eq("flight-dynamics"), anyString(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenThrow(new IllegalStateException("unavailable"));
    when(http.get(
            "mission-definition",
            "/internal/missions",
            com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(List.of()));
    var attempt = new PlanningInputs(http, json, () -> now, mock(StateStore.class)).collect(claim);
    assertEquals("WAITING_INPUTS", attempt.status());
    assertTrue(attempt.issues().contains("NO_CONFIGURED_MISSION"));
    assertEquals(json.tree(live), attempt.request().orElseThrow().value());
    assertTrue(attempt.assets().isEmpty());
  }

  @Test
  void readySpacecraftGetsGeometryBudgetBeforeIncompleteFleetAndTiesRetainRotation() {
    var base = new PlanningRunsTest().asset();
    var evidence = base.catalog().orElseThrow();
    var operations = Optional.of(new PlanningOperations.Captured(evidence, evidence, Map.of()));
    var ready =
        new PlanningInputs.Asset(
            "ready",
            base.inputs(),
            List.of("SAFETY_CLEARANCE"),
            base.catalog(),
            Optional.empty(),
            base.simulationModel(),
            List.of(),
            operations);
    var nextReady =
        new PlanningInputs.Asset(
            "next-ready",
            base.inputs(),
            List.of("SAFETY_CLEARANCE"),
            base.catalog(),
            Optional.empty(),
            base.simulationModel(),
            List.of(),
            operations);
    var missing = new EnumMap<>(base.inputs());
    missing.remove(msc.domain.planning.PlanningDataSnapshot.Input.PROPELLANT);
    var old =
        new PlanningInputs.Asset(
            "old",
            missing,
            List.of("PROPELLANT", "FRESH_SPACECRAFT_STATE"),
            base.catalog(),
            Optional.empty(),
            base.simulationModel(),
            List.of(),
            operations);
    var ordered = PlanningInputs.prioritizeGeometry(List.of(old, nextReady, ready));
    assertEquals(List.of(nextReady, ready, old), ordered);
    assertEquals(List.of(nextReady, ready), ordered.subList(0, 2));
    assertTrue(old.pointGeometry().isEmpty());
    assertEquals(List.of("PROPELLANT", "FRESH_SPACECRAFT_STATE"), old.missing());
  }
}
