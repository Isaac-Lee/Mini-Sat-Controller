package msc.domain.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.planning.ResourceTimeline.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class ResourceTimelineTest {
  private TimeWindow window(long start, long end) {
    return new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(end));
  }

  private final Limits limits = new Limits(100, 20, 100, 1);
  private final Set<Resource> required =
      Set.of(Resource.BATTERY, Resource.STORAGE, Resource.PROPELLANT);

  private Initial initial(double battery, double storage, double propellant) {
    return new Initial(MissionInstant.tai(0), battery, storage, propellant, "estimated-state-v1");
  }

  private Result evaluate(Initial initial, List<Supply> supply, List<Load> loads) {
    return ResourceTimeline.evaluate(
        window(0, 7200), initial, limits, supply, loads, required, "piecewise-model-v1");
  }

  private List<Supply> supply(double watts) {
    return List.of(new Supply(window(0, 7200), watts, 0, "power-forecast-v1"));
  }

  @Test
  void resourceNamesHaveCanonicalApiOrderAndCannotBeMutated() {
    var result = evaluate(initial(100, 0, 2), supply(0), List.of());
    assertEquals(
        List.of(Resource.BATTERY, Resource.STORAGE, Resource.PROPELLANT),
        new ArrayList<>(result.modeledResources()));
    assertThrows(
        UnsupportedOperationException.class, () -> result.modeledResources().add(Resource.THERMAL));
  }

  @Test
  void individuallyFeasibleActivitiesCanExhaustSharedBattery() {
    var first = new Load("a", window(0, 3600), 50, 0, 0, 0);
    var second = new Load("b", window(3600, 7200), 50, 0, 0, 0);
    assertEquals(
        ResourceValidation.Status.VALIDATED,
        evaluate(initial(100, 0, 2), supply(0), List.of(first)).status());
    var combined = evaluate(initial(100, 0, 2), supply(0), List.of(first, second));
    assertEquals(ResourceValidation.Status.REJECTED, combined.status());
    assertEquals(0, combined.trajectory().getLast().batteryWh(), 1e-9);
  }

  @Test
  void eclipseAndBusConsumptionAreAppliedEvenWithoutActivities() {
    var forecast =
        List.of(
            new Supply(window(0, 3600), 40, 10, "sun"),
            new Supply(window(3600, 7200), 0, 10, "eclipse"));
    var result = evaluate(initial(50, 0, 2), forecast, List.of());
    assertEquals(70, result.trajectory().getLast().batteryWh(), 1e-9);
  }

  @Test
  void chargingSaturatesAndCannotCreateFutureBatteryCredit() {
    var result =
        evaluate(
            initial(100, 0, 2),
            List.of(
                new Supply(window(0, 3600), 100, 0, "sun"),
                new Supply(window(3600, 7200), 0, 90, "eclipse")),
            List.of());
    assertEquals(ResourceValidation.Status.REJECTED, result.status());
    assertEquals(10, result.trajectory().getLast().batteryWh(), 1e-9);
  }

  @Test
  void emptyDownlinkDoesNotCreateNegativeStorageCredit() {
    var loads =
        List.of(
            new Load("downlink", window(0, 3600), 0, 0, 1, 0),
            new Load("image", window(3600, 7200), 0, .1, 0, 0));
    var result = evaluate(initial(100, 0, 2), supply(0), loads);
    assertEquals(360, result.trajectory().getLast().storedMb(), 1e-9);
    assertEquals(ResourceValidation.Status.REJECTED, result.status());
  }

  @Test
  void cumulativeBurnsConsumePropellantAtTheirStart() {
    var result =
        evaluate(
            initial(100, 0, 2),
            supply(0),
            List.of(
                new Load("a", window(0, 1), 0, 0, 0, .6),
                new Load("b", window(1, 2), 0, 0, 0, .6)));
    assertEquals(ResourceValidation.Status.REJECTED, result.status());
    assertTrue(
        result.violations().stream()
            .anyMatch(
                v -> v.resource() == Resource.PROPELLANT && v.at().equals(MissionInstant.tai(1))));
  }

  @Test
  void missingSupplyAndUnsupportedRequiredModelsAreNotValidated() {
    assertEquals(
        ResourceValidation.Status.NOT_EVALUATED,
        evaluate(initial(100, 0, 2), List.of(new Supply(window(1, 7200), 0, 0, "gap")), List.of())
            .status());
    var requiredThermal = new HashSet<>(required);
    requiredThermal.add(Resource.THERMAL);
    assertEquals(
        ResourceValidation.Status.NOT_EVALUATED,
        ResourceTimeline.evaluate(
                window(0, 7200),
                initial(100, 0, 2),
                limits,
                supply(0),
                List.of(),
                requiredThermal,
                "model")
            .status());
  }

  @Test
  void simultaneousRatesAreSummedAndInitialViolationsArePreserved() {
    var load = new Load("a", window(0, 7200), 10, 0, 0, 0);
    var other = new Load("b", window(0, 7200), 20, 0, 0, 0);
    var result = evaluate(initial(100, 0, 2), supply(0), List.of(load, other));
    assertEquals(40, result.trajectory().getLast().batteryWh(), 1e-9);
    assertEquals(
        ResourceValidation.Status.REJECTED,
        evaluate(initial(101, 0, 2), supply(0), List.of()).status());
    assertThrows(UnsupportedOperationException.class, () -> result.trajectory().clear());
  }

  @Test
  void outputIncludesBatteryAndStorageSaturationBreakpoints() {
    var result =
        evaluate(
            initial(50, 10, 2),
            supply(100),
            List.of(new Load("downlink", window(0, 3600), 0, 0, .1, 0)));
    var empty =
        result.trajectory().stream()
            .filter(sample -> sample.at().equals(MissionInstant.tai(100)))
            .findFirst()
            .orElseThrow();
    assertEquals(0, empty.storedMb(), 1e-9);
    assertEquals(50 + 100.0 / 36, empty.batteryWh(), 1e-9);
    var full =
        result.trajectory().stream()
            .filter(sample -> sample.at().equals(MissionInstant.tai(1800)))
            .findFirst()
            .orElseThrow();
    assertEquals(100, full.batteryWh(), 1e-9);
    assertEquals(0, full.storedMb(), 1e-9);
  }
}
