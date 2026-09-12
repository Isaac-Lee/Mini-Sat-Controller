package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class KeplerianOrbitAdapterTest {
  private final KeplerianOrbitAdapter adapter = new KeplerianOrbitAdapter();
  private static final double R = 7_000_000, MU = 3.986004418e14;

  private InitialState circular() {
    return new InitialState(
        "sim-orbit-v1",
        "sim-1",
        MissionInstant.tai(1_000_000),
        new Vector(R, 0, 0),
        new Vector(0, Math.sqrt(MU / R), 0),
        "circular analytic fixture");
  }

  @Test
  void matchesIndependentCircularSolutionAndConservesRadius() {
    var initial = circular();
    var window =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(6000_000_000_000L)));
    var result = adapter.predict(initial, window, 30);
    assertEquals(200, result.samples().size());
    var omega = Math.sqrt(MU / (R * R * R));
    for (var sample : result.samples()) {
      var dt = sample.time().seconds() - initial.epoch().seconds();
      var p = sample.positionMeters();
      assertEquals(R * Math.cos(omega * dt), p.x(), 0.00001);
      assertEquals(R * Math.sin(omega * dt), p.y(), 0.00001);
      assertEquals(R, Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z()), 0.00001);
    }
    assertEquals("EME2000", result.frame());
    assertThrows(UnsupportedOperationException.class, () -> result.samples().clear());
  }

  @Test
  void rejectsIntersectingOrbitsStaleInputsAndUnboundedWork() {
    var initial = circular();
    var end = initial.epoch().plus(new MissionDuration(86400_000_000_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.predict(initial, new TimeWindow(initial.epoch(), end), 1));
    var bad =
        new InitialState(
            "bad",
            "sim-1",
            initial.epoch(),
            new Vector(100, 0, 0),
            initial.velocityMetersPerSecond(),
            "bad fixture");
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.predict(bad, new TimeWindow(initial.epoch(), end), 60));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.predict(
                initial,
                new TimeWindow(MissionInstant.tai(3_000_000), MissionInstant.tai(3_000_060)),
                10));
  }

  @Test
  void taiConversionPreservesNanosecondsAndNegativeEpochs() {
    for (var t :
        java.util.List.of(
            new MissionInstant(1_800_000_000, 123456789, TimeScale.TAI),
            new MissionInstant(-12, 987654321, TimeScale.TAI)))
      assertEquals(t, adapter.instant(adapter.date(t)));
  }
}
