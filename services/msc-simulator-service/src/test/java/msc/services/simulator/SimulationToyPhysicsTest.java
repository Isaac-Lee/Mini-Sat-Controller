package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.*;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy.*;
import msc.domain.monitoring.OperationalTelemetry.Mode;
import msc.domain.shared.Ids.*;
import msc.domain.spacecraftcontrol.CommandInstance;
import msc.domain.time.*;
import msc.platform.Json;
import msc.services.simulator.SimulationCommandApi.Entry;
import msc.services.simulator.SimulationCommandApi.Status;
import msc.services.simulator.SimulationToyPhysics.*;
import org.junit.jupiter.api.Test;

class SimulationToyPhysicsTest {
  // 10 ticks per second; tick t is TAI 1000 + t/10 s.
  final Correlation clock =
      new Correlation(
          "sim",
          "m1",
          "c1",
          "p1",
          MissionInstant.tai(1000),
          0,
          10,
          new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
          "SIMULATION",
          "test",
          "synthetic-test");
  // 100 Wh battery, 10 Wh floor, 100 MB storage, 10 kg propellant, 36 W constant recharge.
  final MissionProfile mission =
      new MissionProfile("sim", "catalog", 1, "m1", 100, 10, 100, 10, 36, "c1", "synthetic-test");

  Entry op(String id, Operation operation, long completion, double watts, double megabytes) {
    var catalog =
        new CatalogEntry(
            "catalog-" + id,
            1,
            new ActivityDefinition(
                new ActivityDefinitionId("activity"),
                1,
                "synthetic",
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                RiskClass.LOW,
                "template:1"),
            new CommandTemplate("template", 1, operation.name(), Map.of()),
            new ResourceProfile(watts, megabytes, 1),
            Requirement.HUMAN_APPROVAL,
            10,
            "synthetic-test");
    var profile =
        new OperationResourceProfile(
            operation,
            new CatalogReference(catalog.id(), 1),
            new ExpectedCatalogResources(watts, megabytes, 1),
            operation == Operation.DOWNLINK ? 10.0 : null);
    var command =
        new CommandInstance(
            new CommandId(id),
            "template:1",
            Map.of(),
            new OnboardTime(completion - 100, "p1", new TimeCorrelationId("c1")));
    return new Entry(
        command, catalog, "sha", profile, completion - 100, completion, Status.PENDING, null);
  }

  Setup setup(double battery, double stored, List<Entry> ops, double dropout) {
    return new Setup(
        "sim",
        "simulator:toy-model:test",
        1,
        mission,
        clock,
        0,
        1000,
        100,
        new State(battery, stored, 10),
        ops,
        List.of(new TimeWindow(MissionInstant.tai(1020), MissionInstant.tai(1050))),
        dropout);
  }

  Setup nominal(double dropout) {
    return setup(
        50,
        0,
        List.of(
            op("image", Operation.IMAGE, 300, 360, 40), op("dl", Operation.DOWNLINK, 600, 36, 0)),
        dropout);
  }

  @Test
  void sameSeedIsDeterministicAndDifferentSeedDiffers() {
    var a = SimulationToyPhysics.record(nominal(0.5), 42);
    assertEquals(a, SimulationToyPhysics.record(nominal(0.5), 42));
    assertEquals(SimulationToyPhysics.replay(a), SimulationToyPhysics.replay(a));
    assertNotEquals(a.events(), SimulationToyPhysics.record(nominal(0.5), 43).events());
  }

  @Test
  void replayOfSerializedLogReproducesIdenticalTelemetry() {
    var json = new Json(JsonMapper.builder().findAndAddModules().build());
    var log = SimulationToyPhysics.record(nominal(0.3), 7);
    var original = SimulationToyPhysics.replay(log);
    var stored = json.read(json.write(log), EventLog.class);
    assertEquals(log, stored);
    assertEquals(original, SimulationToyPhysics.replay(stored));
    assertEquals(json.write(original), json.write(SimulationToyPhysics.replay(stored)));
  }

  @Test
  void integratesExplicitUnitsAndLabelsEveryOutput() {
    var samples = SimulationToyPhysics.replay(SimulationToyPhysics.record(nominal(0), 1));
    assertEquals(10, samples.size());
    samples.forEach(
        s -> {
          assertEquals("TOY_MODEL", s.fidelity());
          assertEquals("SIMULATION", s.environment());
          assertTrue(s.frame().provenance().startsWith("TOY_MODEL SIMULATION"));
        });
    // 36 W for 10 s = 0.1 Wh per 100-tick step.
    assertEquals(50.1, samples.get(0).frame().batteryWh(), 1e-9);
    // IMAGE at tick 300: 360 W x 10 s = 1 Wh debited, +40 MB, -1 kg.
    var image = samples.get(2);
    assertEquals(50.3 - 1, image.frame().batteryWh(), 1e-9);
    assertEquals(40, image.frame().storageMb());
    assertEquals(9, image.frame().propellantKg());
    assertEquals("APPLIED", image.completions().get(0).outcome());
    // DOWNLINK at tick 600 drains 10 MB/s x 10 s = 100 MB, capped at the 40 MB stored.
    assertEquals(0, samples.get(5).frame().storageMb());
    assertEquals(8, samples.get(5).frame().propellantKg());
  }

  @Test
  void linkFlagFollowsVisibilityWindowAndSeededDropout() {
    var samples = SimulationToyPhysics.replay(SimulationToyPhysics.record(nominal(0), 1));
    // Window is TAI [1020, 1050): ticks 200..400 inclusive, 500 excluded.
    var flags = samples.stream().map(Sample::linkAvailable).toList();
    assertEquals(
        List.of(false, true, true, true, false, false, false, false, false, false), flags);
    assertTrue(
        SimulationToyPhysics.replay(SimulationToyPhysics.record(nominal(1), 1)).stream()
            .noneMatch(Sample::linkAvailable));
  }

  @Test
  void batteryFloorRejectsOperationWithoutPartialEffect() {
    // 3600 W x 10 s = 10 Wh; from 19 Wh (+0.3 Wh recharge) it would breach the 10 Wh floor.
    var heavy = op("heavy", Operation.IMAGE, 300, 3600, 40);
    var samples =
        SimulationToyPhysics.replay(
            SimulationToyPhysics.record(setup(19, 0, List.of(heavy), 0), 1));
    var step = samples.get(2);
    assertEquals("BATTERY_FLOOR", step.completions().get(0).outcome());
    assertEquals(Mode.SAFE, step.frame().mode());
    assertEquals(19.3, step.frame().batteryWh(), 1e-9);
    assertEquals(0, step.frame().storageMb());
    assertEquals(10, step.frame().propellantKg());
    assertEquals(Mode.NOMINAL, samples.get(3).frame().mode());
  }

  @Test
  void rechargeIsCappedAtBatteryCapacity() {
    var samples =
        SimulationToyPhysics.replay(SimulationToyPhysics.record(setup(100, 0, List.of(), 0), 1));
    assertTrue(samples.stream().allMatch(s -> s.frame().batteryWh() == 100));
  }

  @Test
  void fullStorageRejectsImageAndKeepsBatteryAndStorage() {
    var image = op("image", Operation.IMAGE, 300, 360, 40);
    var step =
        SimulationToyPhysics.replay(
                SimulationToyPhysics.record(setup(50, 100, List.of(image), 0), 1))
            .get(2);
    assertEquals("STORAGE_CAPACITY_EXCEEDED", step.completions().get(0).outcome());
    assertEquals(0, step.completions().get(0).energyWh());
    assertEquals(100, step.frame().storageMb());
    assertEquals(50.3, step.frame().batteryWh(), 1e-9);
  }

  @Test
  void tamperedOrMislabelledLogsAreRejected() {
    var log = SimulationToyPhysics.record(nominal(0), 1);
    var shifted = new ArrayList<>(log.events());
    shifted.set(0, new Event(1, shifted.get(0).frameId(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulationToyPhysics.replay(
                new EventLog(log.fidelity(), log.environment(), 1, log.setup(), shifted)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EventLog("HIGH_FIDELITY", "SIMULATION", 1, log.setup(), log.events()));
    assertThrows(
        IllegalArgumentException.class,
        () -> setup(101, 0, List.of(), 0));
  }
}
