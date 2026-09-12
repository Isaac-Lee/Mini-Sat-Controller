package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.PropellantContracts;
import msc.domain.flightdynamics.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.monitoring.TelemetryObservation;
import msc.domain.planning.*;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import msc.platform.StateStore;
import msc.services.planning.PlanningInputs.*;
import org.junit.jupiter.api.Test;

class PlanningResourcesTest {
  final PlanningRunsTest runs = new PlanningRunsTest();
  final PlanningGeometryTest f = runs.fixtures;
  final PlanningResources resources = new PlanningResources(f.json);
  final String craft = "norad-63229";

  Asset asset(double battery, double stored) {
    var base = runs.asset();
    var inputs = new EnumMap<Input, Evidence>(base.inputs());
    var at = MissionInstant.tai(990);
    var binding = new Binding(craft, 1, "simulator:test", Environment.SIMULATION, 300, 0, "test");
    var frame =
        new Frame(
            UUID.randomUUID(),
            craft,
            1,
            binding.source(),
            1,
            at,
            TelemetryObservation.Quality.GOOD,
            Mode.NOMINAL,
            battery,
            stored,
            10,
            "test");
    var source = Estimate.empty(binding).observe(frame, at).estimate();
    inputs.put(
        Input.SPACECRAFT_STATE,
        f.evidence(Map.of("estimate", Map.of("version", 1, "body", source))));
    var mission = new MissionProfile(craft, "catalog", 1, "v1", 100, 10, 10, 10, 0, "sim", "test");
    inputs.put(Input.MISSION_DEFINITION, f.evidence(Map.of("version", 1, "body", mission)));
    var model =
        new PropellantContracts.Model(
            craft,
            "v1",
            "SIMULATION",
            1,
            new BigDecimal("0.1"),
            new BigDecimal("0.001"),
            3600,
            "test");
    var estimate =
        new PropellantEstimate(
            new PropellantEstimateId("mass"),
            new SpacecraftId(craft),
            new EstimateContext(
                at,
                "SCALAR_MASS_KG",
                "bound",
                "sim",
                "v1",
                new SnapshotRef(new SnapshotId("telemetry"), 1, "test", at)),
            BigDecimal.TEN);
    var snapshot =
        new PropellantContracts.Snapshot(
            "mass", estimate, 1, source, 1, model, at, MissionInstant.tai(4590));
    inputs.put(Input.PROPELLANT, f.evidence(Map.of("id", "mass", "version", 1, "body", snapshot)));
    return new Asset(
        craft,
        inputs,
        List.of(),
        base.catalog(),
        base.pointGeometry(),
        base.simulationModel(),
        base.activityOptions());
  }

  PlanningResources.Assessment evaluate(
      Asset asset, List<MissionSchedule> schedules, Map<String, Evidence> profiles) {
    var attempt = runs.attempt("request", 1, asset);
    var run = new PlanningRuns(mock(StateStore.class), f.json).derive(attempt, asset);
    return resources.derive(attempt, asset, run, schedules, profiles, true);
  }

  MissionSchedule existing(String id, long start, long end) {
    var c = f.json.convert(f.catalog().value(), CatalogEntry.class);
    var window = new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(end));
    var activity =
        new ScheduledActivity(
            new ActivityId(id),
            c.activity().id(),
            c.activity().version(),
            window,
            c.activity().exclusiveResources());
    return MissionSchedule.restore(
        new MissionSchedule.Snapshot(
            new ScheduleKey(
                new SpacecraftId(craft),
                new TimeWindow(MissionInstant.tai(900), MissionInstant.tai(1200))),
            1,
            MissionInstant.tai(900),
            List.of(activity),
            List.of(
                new Assignment(
                    new AssignmentId("assignment-" + id),
                    new CandidateId("candidate-" + id),
                    new RequestId("existing"),
                    new PlanningRunId("existing"),
                    activity.id())),
            ResourceValidation.notEvaluated()));
  }

  MissionSchedule withCatalog(MissionSchedule original, Evidence evidence) {
    var catalog = f.json.convert(evidence.value(), CatalogEntry.class);
    var old = original.activities().getFirst();
    return MissionSchedule.restore(
        new MissionSchedule.Snapshot(
            original.key(),
            original.version(),
            original.frozenUntil(),
            List.of(
                new ScheduledActivity(
                    old.id(),
                    catalog.activity().id(),
                    catalog.activity().version(),
                    old.window(),
                    catalog.activity().exclusiveResources())),
            original.assignments(),
            original.resourceValidation()));
  }

  @Test
  void approvedDownlinkNetsItsOwnGeneratedDataAgainstExplicitDrain() {
    var operations = new PlanningOperationsTest();
    var downlink = operations.catalog("DOWNLINK", .5);
    var activity = withCatalog(existing("contact", 1050, 1060), downlink);
    var earlier = existing("earlier-image", 1000, 1010);
    var asset = asset(50, 8.5);
    var attempt = runs.attempt("request", 1, asset);
    var run = new PlanningRuns(mock(StateStore.class), f.json).derive(attempt, asset);
    var profile = operations.profiles(downlink, .1);
    var result =
        resources.derive(
            attempt,
            asset,
            run,
            List.of(earlier, activity),
            Map.of("earlier-image", f.catalog(), "contact", downlink),
            true,
            Map.of("contact", profile));
    var forecast = result.candidates().getFirst().forecast().orElseThrow();
    assertEquals(ResourceValidation.Status.VALIDATED, forecast.status());
    assertEquals(10, forecast.trajectory().getLast().storedMb(), 1e-10);
    assertEquals(profile, result.activityOperationProfiles().get("contact"));
  }

  @Test
  void emptyDownlinkCannotCreateStorageCreditForLaterImaging() {
    var operations = new PlanningOperationsTest();
    var downlink = operations.catalog("DOWNLINK", .5);
    var schedule = withCatalog(existing("contact", 1000, 1010), downlink);
    var asset = asset(50, 0);
    var attempt = runs.attempt("request", 1, asset);
    var run = new PlanningRuns(mock(StateStore.class), f.json).derive(attempt, asset);
    var result =
        resources.derive(
            attempt,
            asset,
            run,
            List.of(schedule),
            Map.of("contact", downlink),
            true,
            Map.of("contact", operations.profiles(downlink, 2)));
    assertEquals(
        1,
        result.candidates().getFirst().forecast().orElseThrow().trajectory().getLast().storedMb(),
        1e-10);
    var missing =
        resources.derive(attempt, asset, run, List.of(schedule), Map.of("contact", downlink), true);
    assertTrue(missing.candidates().getFirst().forecast().isEmpty());
    assertTrue(
        missing
            .candidates()
            .getFirst()
            .issues()
            .contains("OPERATION_RESOURCE_MODEL_REQUIRED:contact"));
  }

  @Test
  void forecastStartsAtTelemetryAndSubtractsWaitingBusDrawAndMassUncertainty() {
    var result = evaluate(asset(50, 0), List.of(), Map.of());
    var candidate = result.candidates().getFirst();
    var forecast = candidate.forecast().orElseThrow();
    assertTrue(candidate.issues().isEmpty());
    assertEquals(MissionInstant.tai(990), candidate.horizon().orElseThrow().start());
    assertEquals(ResourceValidation.Status.VALIDATED, forecast.status());
    var end = forecast.trajectory().getLast();
    assertEquals(50 - (120 * 5 + 10) / 3600.0, end.batteryWh(), 1e-10);
    assertEquals(1, end.storedMb(), 1e-10);
    assertEquals(9.78, end.propellantKg(), 1e-10);
    assertEquals(result, f.json.read(f.json.write(result), PlanningResources.Assessment.class));
  }

  @Test
  void waitingPeriodCanRejectAnOtherwiseCheapCandidate() {
    var candidate = evaluate(asset(10.1, 0), List.of(), Map.of()).candidates().getFirst();
    assertEquals(ResourceValidation.Status.REJECTED, candidate.forecast().orElseThrow().status());
    assertTrue(
        candidate.forecast().orElseThrow().violations().stream()
            .anyMatch(v -> v.resource() == ResourceTimeline.Resource.BATTERY));
  }

  @Test
  void committedActivityUsesItsOwnPinnedCatalogAndCanOverflowStorage() {
    var schedule = existing("other", 1000, 1010);
    var candidateOnly = evaluate(asset(50, 8.5), List.of(), Map.of()).candidates().getFirst();
    assertEquals(
        ResourceValidation.Status.VALIDATED, candidateOnly.forecast().orElseThrow().status());
    var result = evaluate(asset(50, 8.5), List.of(schedule), Map.of("other", f.catalog()));
    assertEquals(
        ResourceValidation.Status.REJECTED,
        result.candidates().getFirst().forecast().orElseThrow().status());
    assertEquals(schedule.snapshot(), result.scheduleHeads().getFirst());
    assertEquals(
        10.5,
        result.candidates().getFirst().forecast().orElseThrow().trajectory().getLast().storedMb(),
        1e-10);
  }

  @Test
  void sameDefinitionDoesNotAuthorizeGuessingAnExistingCatalogProfile() {
    var result = evaluate(asset(50, 0), List.of(existing("unknown", 1000, 1010)), Map.of());
    var candidate = result.candidates().getFirst();
    assertTrue(candidate.forecast().isEmpty());
    assertTrue(candidate.issues().contains("PINNED_RESOURCE_PROFILE_REQUIRED:unknown"));
    assertTrue(candidate.issues().contains("INCOMPLETE_ACTIVITY_LOAD_COVERAGE"));
  }

  @Test
  void unreadScheduleContextCannotBeConfusedWithAnEmptyFuture() {
    var asset = asset(50, 0);
    var attempt = runs.attempt("request", 1, asset);
    var run = new PlanningRuns(mock(StateStore.class), f.json).derive(attempt, asset);
    var result = resources.derive(attempt, asset, run, List.of(), Map.of(), false);
    assertEquals("SCHEDULE_HEADS_UNREAD", result.scheduleContextStatus());
    assertTrue(result.candidates().getFirst().forecast().isEmpty());
    assertTrue(result.candidates().getFirst().issues().contains("SCHEDULE_HEADS_UNREAD"));
  }

  @Test
  void corruptedCatalogHasADistinctIntegrityIssueAndCannotPass() {
    var bad = new Evidence("owner", "/test", "bad-hash", f.catalog().value());
    var result =
        evaluate(asset(50, 0), List.of(existing("other", 1000, 1010)), Map.of("other", bad));
    var candidate = result.candidates().getFirst();
    assertTrue(candidate.forecast().isEmpty());
    assertTrue(candidate.issues().contains("RESOURCE_CATALOG_EVIDENCE_HASH_MISMATCH:other"));
  }

  @Test
  void laterCommitmentExtendsForecastBeyondCandidateEndAndCanRejectIt() {
    var result =
        evaluate(
            asset(50, 8.5), List.of(existing("later", 1150, 1160)), Map.of("later", f.catalog()));
    var candidate = result.candidates().getFirst();
    assertEquals(MissionInstant.tai(1160), candidate.horizon().orElseThrow().end());
    assertEquals(ResourceValidation.Status.REJECTED, candidate.forecast().orElseThrow().status());
    assertEquals(10.5, candidate.forecast().orElseThrow().trajectory().getLast().storedMb(), 1e-10);
  }

  @Test
  void inFlightActivityIsClippedAtTelemetryWithoutGeneratingHistoricalStorageAgain() {
    var result =
        evaluate(
            asset(50, 0),
            List.of(existing("in-flight", 985, 995)),
            Map.of("in-flight", f.catalog()));
    assertEquals(
        1.5,
        result.candidates().getFirst().forecast().orElseThrow().trajectory().getLast().storedMb(),
        1e-10);
  }
}
