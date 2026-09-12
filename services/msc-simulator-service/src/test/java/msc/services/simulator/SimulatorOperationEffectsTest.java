package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.*;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy.*;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.services.simulator.SimulatorOperationEffects.*;
import org.junit.jupiter.api.Test;

class SimulatorOperationEffectsTest {
  private MissionProfile mission(double capacity, double propellant) {
    return new MissionProfile(
        "sim", "catalog", 1, "v1", 100, 10, capacity, propellant, 0, "clock", "synthetic-test");
  }

  private CatalogEntry catalog(Operation operation, double produced, double consumed) {
    return new CatalogEntry(
        "catalog",
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
        new ResourceProfile(25, produced, consumed),
        Requirement.HUMAN_APPROVAL,
        10,
        "synthetic-test");
  }

  private OperationResourceProfile profile(CatalogEntry catalog) {
    var r = catalog.resources();
    var operation = Operation.valueOf(catalog.template().operation());
    return new OperationResourceProfile(
        operation,
        new CatalogReference(catalog.id(), catalog.version()),
        new ExpectedCatalogResources(
            r.powerWatts(), r.generatedMegabytes(), r.propellantKilograms()),
        operation == Operation.DOWNLINK ? 10.0 : null);
  }

  @Test
  void imageThenDownlinkUsesExplicitEffectsAndDebitsBothOperations() {
    var mission = mission(200, 10);
    var image = catalog(Operation.IMAGE, 50, 2);
    var first =
        SimulatorOperationEffects.complete(mission, new Reservoirs(20, 10), image, profile(image));
    assertEquals(Outcome.APPLIED, first.outcome());
    assertEquals(new Reservoirs(70, 8), first.after());
    var link = catalog(Operation.DOWNLINK, 5, 1);
    var second = SimulatorOperationEffects.complete(mission, first.after(), link, profile(link));
    assertEquals(new Reservoirs(0, 7), second.after());
    assertEquals(100, second.attemptedDrainMegabytes());
    assertEquals(75, second.actualDrainMegabytes());
    assertEquals(25, second.declaredPowerWatts());
    // An empty reservoir cannot accumulate credit against the next image.
    assertEquals(
        50,
        SimulatorOperationEffects.complete(mission, second.after(), image, profile(image))
            .after()
            .storedMegabytes());
  }

  @Test
  void productionPeakRejectsEvenWhenDownlinkWouldEmptyStorage() {
    var link = catalog(Operation.DOWNLINK, 50, 1);
    var before = new Reservoirs(80, 10);
    var result = SimulatorOperationEffects.complete(mission(100, 10), before, link, profile(link));
    assertEquals(Outcome.STORAGE_CAPACITY_EXCEEDED, result.outcome());
    assertEquals(before, result.after());
    assertEquals(0, result.actualDrainMegabytes());
    assertEquals(0, result.consumedPropellantKilograms());
  }

  @Test
  void insufficientPropellantRejectsWithoutPartialStorageEffect() {
    for (var operation : new Operation[] {Operation.IMAGE, Operation.DOWNLINK}) {
      var catalog = catalog(operation, 10, 2);
      var before = new Reservoirs(20, 1);
      var result =
          SimulatorOperationEffects.complete(mission(100, 10), before, catalog, profile(catalog));
      assertEquals(Outcome.INSUFFICIENT_PROPELLANT, result.outcome());
      assertEquals(before, result.after());
      assertEquals(0, result.generatedMegabytes());
    }
  }

  @Test
  void maneuverCannotBeReportedAsApplied() {
    var catalog = catalog(Operation.MANEUVER, 10, 2);
    var before = new Reservoirs(20, 10);
    var result =
        SimulatorOperationEffects.complete(mission(100, 10), before, catalog, profile(catalog));
    assertEquals(Outcome.NOT_SUPPORTED, result.outcome());
    assertEquals(before, result.after());
  }

  @Test
  void invalidInitialValuesAndMismatchedEvidenceAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Reservoirs(Double.NaN, 1));
    assertThrows(IllegalArgumentException.class, () -> new Reservoirs(1, -1));
    var catalog = catalog(Operation.IMAGE, 1, 0);
    for (var invalid : new Reservoirs[] {new Reservoirs(101, 1), new Reservoirs(1, 11)})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SimulatorOperationEffects.complete(
                  mission(100, 10), invalid, catalog, profile(catalog)));
    var wrong =
        new OperationResourceProfile(
            Operation.IMAGE,
            new CatalogReference("catalog", 2),
            new ExpectedCatalogResources(25, 1, 0),
            null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulatorOperationEffects.complete(
                mission(100, 10), new Reservoirs(0, 1), catalog, wrong));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulatorOperationEffects.complete(
                mission(100, 10),
                new Reservoirs(0, 1),
                catalog(Operation.IMAGE, 2, 0),
                profile(catalog)));
  }

  @Test
  void nonfiniteArithmeticNeverEscapesAsSuccessfulState() {
    var catalog = catalog(Operation.IMAGE, Double.MAX_VALUE, 0);
    var before = new Reservoirs(Double.MAX_VALUE, 1);
    var result =
        SimulatorOperationEffects.complete(
            mission(Double.MAX_VALUE, 10), before, catalog, profile(catalog));
    assertEquals(Outcome.NONFINITE_EFFECT, result.outcome());
    assertEquals(before, result.after());
  }
}
