package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.domain.anomaly.MissionPhase;
import msc.domain.planning.*;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.time.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.*;
import org.junit.jupiter.api.Test;

class PlanningRunsTest {
  final PlanningGeometryTest fixtures = new PlanningGeometryTest();
  final Json json = fixtures.json;

  Asset asset() {
    var inputs = new EnumMap<Input, Evidence>(Input.class);
    for (var input : Input.values()) inputs.put(input, fixtures.evidence(Map.of("input", input)));
    var model = new PlanningOptionsTest().model(MissionPhase.ROUTINE);
    var catalog = json.convert(fixtures.catalog().value(), CatalogEntry.class);
    var option =
        new PlanningOptions.Option(
            "norad-63229",
            catalog.activity().id().value(),
            catalog.activity().version(),
            new TimeWindow(MissionInstant.tai(1100), MissionInstant.tai(1110)),
            model.phase(),
            model.mode(),
            FeasibilityEvaluation.Status.NOT_EVALUATED,
            List.of("AOI_SENSOR_COVERAGE", "RESOURCE_TIMELINE", "SCHEDULE_CONFLICTS"));
    return new Asset(
        "norad-63229",
        inputs,
        List.of("SAFETY_CLEARANCE"),
        Optional.of(fixtures.catalog()),
        Optional.of(fixtures.evidence(Map.of("area", fixtures.area, "searchKey", "test-geometry"))),
        Optional.of(fixtures.evidence(Map.of("version", 2, "body", model))),
        List.of(option));
  }

  Attempt attempt(String request, long revision, Asset asset) {
    return new Attempt(
        UUID.randomUUID().toString(),
        request,
        revision,
        MissionInstant.tai(1000),
        "WAITING_INPUTS",
        Optional.of(fixtures.evidence(Map.of("revision", revision))),
        List.of(asset),
        List.of());
  }

  @Test
  void recordedRunRetainsRequestRevisionAndExactSourcesWithoutGrantingFeasibility() {
    var asset = asset();
    var attempt = attempt(UUID.randomUUID().toString(), 7, asset);
    var result = new PlanningRuns(mock(StateStore.class), json).derive(attempt, asset);
    assertEquals(7, result.requestRevision());
    assertEquals(attempt.id(), result.inputAttemptId());
    assertEquals(asset.catalog().orElseThrow(), result.catalog());
    assertEquals(asset.simulationModel().orElseThrow(), result.simulationModel());
    for (var input : Input.values())
      assertEquals(asset.inputs().get(input).sha256(), result.sourceHashes().get(input));
    var restored = json.read(json.write(result), PlanningRuns.Published.class);
    assertEquals(result, restored);
    var catalog = json.convert(result.catalog().value(), CatalogEntry.class);
    var domain = PlanningRun.restore(restored.run(), (id, version) -> catalog.activity());
    assertEquals(
        FeasibilityEvaluation.Status.NOT_EVALUATED,
        domain.candidates().getFirst().feasibility().status());
    assertTrue(domain.decisions().getFirst().rationale().contains("SAFETY_CLEARANCE"));
    assertTrue(domain.decisions().getFirst().rationale().contains("RESOURCE_TIMELINE"));
  }

  @Test
  void incompleteInputsDoNotFabricateARunAndTamperedEvidenceIsRejected() {
    var full = asset();
    var inputs = new EnumMap<Input, Evidence>(full.inputs());
    inputs.remove(Input.PROPELLANT);
    var incomplete =
        new Asset(
            full.spacecraftId(),
            inputs,
            full.missing(),
            full.catalog(),
            full.pointGeometry(),
            full.simulationModel(),
            full.activityOptions());
    var store = mock(StateStore.class);
    var attempt = attempt(UUID.randomUUID().toString(), 1, incomplete);
    new PlanningRuns(store, json).publish(attempt);
    verify(store)
        .create(
            "planning-run-index", attempt.id(), new PlanningRuns.Index(attempt.id(), List.of()));
    verifyNoMoreInteractions(store);
    inputs.put(
        Input.PROPELLANT, new Evidence("owner", "/source", "wrong-hash", json.tree(Map.of())));
    var tampered =
        new Asset(
            full.spacecraftId(),
            inputs,
            full.missing(),
            full.catalog(),
            full.pointGeometry(),
            full.simulationModel(),
            full.activityOptions());
    assertThrows(ApiException.class, () -> new PlanningRuns(store, json).derive(attempt, tampered));
  }
}
