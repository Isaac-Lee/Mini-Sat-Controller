package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import msc.contracts.PointingContracts.*;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

/**
 * Pure record-validation unit tests for {@link PointingContracts}: no Orekit, no DB, no Spring.
 * Numeric correctness of the geometry itself is proven by {@code
 * msc.orbit.RequiredTargetPointingCalculatorIT} (msc-orbit-adapter) against an independently built
 * Orekit reference; this file only proves the contract's own defensive shape -- unit-vector
 * enforcement, range checks, the single-scope-value invariant, and that a query never carries an
 * off-nadir limit field for anything to accidentally gate on.
 */
class PointingContractsTest {
  private static MissionInstant t(long seconds) {
    return MissionInstant.tai(seconds);
  }

  private Target target() {
    return new Target("t1", 10.0, 20.0, 0.0);
  }

  private Trajectory.GroundPoint groundPoint() {
    return new Trajectory.GroundPoint(t(0), 10.0, 20.0, 500_000.0, "digest");
  }

  private RequiredTargetPointingSample sample(
      Trajectory.Vector earthFixedUnit, Trajectory.Vector inertialUnit) {
    return new RequiredTargetPointingSample(
        t(0),
        new Trajectory.Vector(7_000_000, 0, 0),
        earthFixedUnit,
        inertialUnit,
        12.5,
        500_000,
        groundPoint(),
        target(),
        45.0,
        90.0,
        true);
  }

  @Test
  void rejectsNonUnitLineOfSightVectors() {
    var unit = new Trajectory.Vector(1, 0, 0);
    var notUnit = new Trajectory.Vector(2, 0, 0);
    assertThrows(IllegalArgumentException.class, () -> sample(notUnit, unit));
    assertThrows(IllegalArgumentException.class, () -> sample(unit, notUnit));
    // A genuine unit vector (within floating tolerance) is accepted.
    assertDoesNotThrow(() -> sample(unit, unit));
  }

  @Test
  void rejectsOutOfRangeOffNadirElevationAndAzimuth() {
    var unit = new Trajectory.Vector(1, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                -1,
                500_000,
                groundPoint(),
                target(),
                45,
                90,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                181,
                500_000,
                groundPoint(),
                target(),
                45,
                90,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                10,
                500_000,
                groundPoint(),
                target(),
                91,
                90,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                10,
                500_000,
                groundPoint(),
                target(),
                45,
                360,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                10,
                500_000,
                groundPoint(),
                target(),
                45,
                -1,
                true));
  }

  @Test
  void rejectsNonPositiveSlantRange() {
    var unit = new Trajectory.Vector(1, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingSample(
                t(0),
                new Trajectory.Vector(7e6, 0, 0),
                unit,
                unit,
                10,
                0,
                groundPoint(),
                target(),
                45,
                90,
                true));
  }

  @Test
  void queryRejectsOutOfRangeStepAndElevationMask() {
    var horizon = new TimeWindow(t(0), t(100));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequiredTargetPointingQuery(target(), horizon, 0, 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequiredTargetPointingQuery(target(), horizon, 3601, 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequiredTargetPointingQuery(target(), horizon, 10, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequiredTargetPointingQuery(target(), horizon, 10, 90));
    assertDoesNotThrow(() -> new RequiredTargetPointingQuery(target(), horizon, 10, 0));
  }

  // C2/C3: this contract must never carry a field that could become a hidden feasibility gate.
  // There is deliberately no off-nadir limit anywhere on the query, and exactly one scope value.
  @Test
  void queryHasNoOffNadirLimitFieldAndScopeHasExactlyOneNonFeasibleValue() {
    for (var recordComponent : RequiredTargetPointingQuery.class.getRecordComponents())
      assertFalse(
          recordComponent.getName().toLowerCase().contains("offnadir"),
          "RequiredTargetPointingQuery must never gate on an off-nadir limit");
    var values = RequiredTargetPointingScope.values();
    assertEquals(1, values.length);
    assertEquals("SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE", values[0].name());
    assertTrue(
        java.util.Arrays.stream(values).noneMatch(v -> v.name().contains("FEASIBLE")),
        "No scope value may ever claim FEASIBLE");
  }

  @Test
  void resultRejectsEmptySamplesAndBlankIdentity() {
    var horizon = new TimeWindow(t(0), t(100));
    var query = new RequiredTargetPointingQuery(target(), horizon, 10, 0);
    var unit = new Trajectory.Vector(1, 0, 0);
    var oneSample = List.of(sample(unit, unit));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingResult(
                "sol-1",
                "sc-1",
                "model-a",
                "model-b",
                "hash",
                "digest",
                query,
                List.of(),
                RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RequiredTargetPointingResult(
                "",
                "sc-1",
                "model-a",
                "model-b",
                "hash",
                "digest",
                query,
                oneSample,
                RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE));
    assertDoesNotThrow(
        () ->
            new RequiredTargetPointingResult(
                "sol-1",
                "sc-1",
                "model-a",
                "model-b",
                "hash",
                "digest",
                query,
                oneSample,
                RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE));
  }

  @Test
  void targetIsCarriedVerbatimPerSampleAndEqualsQueryTarget() {
    var unit = new Trajectory.Vector(1, 0, 0);
    var s = sample(unit, unit);
    assertEquals(target(), s.target());
  }
}
