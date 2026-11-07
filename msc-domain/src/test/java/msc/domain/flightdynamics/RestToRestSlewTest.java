package msc.domain.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import msc.domain.flightdynamics.RestToRestSlew.Shape;
import org.junit.jupiter.api.Test;

class RestToRestSlewTest {
  // 2 deg/s, 0.5 deg/s²: the rate limit is first reached at 2²/0.5 = 8 degrees.
  static final double RATE = 2;
  static final double ACCELERATION = 0.5;
  static final double SETTLING = 5;

  @Test
  void zeroAngleNeedsNoMotionOrSettling() {
    var p = RestToRestSlew.profile(0, RATE, ACCELERATION, SETTLING);
    assertEquals(Shape.NONE, p.shape());
    assertEquals(0, p.totalSeconds());
    assertEquals(0, p.settlingSeconds());
  }

  @Test
  void shortSlewIsTriangularAndStaysBelowTheRateLimit() {
    var p = RestToRestSlew.profile(2, RATE, ACCELERATION, SETTLING);
    assertEquals(Shape.TRIANGULAR, p.shape());
    assertEquals(4, p.slewSeconds(), 1e-12);
    assertEquals(1, p.peakRateDegreesPerSecond(), 1e-12);
    assertEquals(9, p.totalSeconds(), 1e-12);
  }

  @Test
  void triangularAndTrapezoidalAgreeAtTheBoundary() {
    var at = RestToRestSlew.profile(8, RATE, ACCELERATION, 0);
    assertEquals(Shape.TRIANGULAR, at.shape());
    assertEquals(8, at.slewSeconds(), 1e-12);
    assertEquals(RATE, at.peakRateDegreesPerSecond(), 1e-12);
    var above = RestToRestSlew.profile(8 + 1e-9, RATE, ACCELERATION, 0);
    assertEquals(Shape.TRAPEZOIDAL, above.shape());
    assertEquals(at.slewSeconds(), above.slewSeconds(), 1e-6);
  }

  @Test
  void longSlewCoastsAtTheRateLimit() {
    var p = RestToRestSlew.profile(20, RATE, ACCELERATION, SETTLING);
    assertEquals(Shape.TRAPEZOIDAL, p.shape());
    assertEquals(RATE, p.peakRateDegreesPerSecond());
    // 4 s accelerating + 6 s coasting (12 deg) + 4 s decelerating.
    assertEquals(14, p.slewSeconds(), 1e-12);
    assertEquals(19, p.totalSeconds(), 1e-12);
    // Doubling acceleration shortens only the ramps: 20/2 + 2/1 = 12 s.
    assertEquals(12, RestToRestSlew.profile(20, RATE, 1, 0).slewSeconds(), 1e-12);
    // Doubling rate makes the same slew triangular again (boundary 32 deg): 2*sqrt(40) s.
    var faster = RestToRestSlew.profile(20, 4, ACCELERATION, 0);
    assertEquals(Shape.TRIANGULAR, faster.shape());
    assertEquals(2 * Math.sqrt(40), faster.slewSeconds(), 1e-12);
  }

  @Test
  void rejectsInvalidLimits() {
    assertThrows(IllegalArgumentException.class, () -> RestToRestSlew.profile(-1, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> RestToRestSlew.profile(181, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> RestToRestSlew.profile(1, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> RestToRestSlew.profile(1, 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> RestToRestSlew.profile(1, 1, 1, -1));
    assertThrows(
        IllegalArgumentException.class, () -> RestToRestSlew.profile(Double.NaN, 1, 1, 0));
  }

  @Test
  void angleBetweenDirections() {
    var x = new Trajectory.Vector(1, 0, 0);
    assertEquals(0, RestToRestSlew.angleDegrees(x, x));
    assertEquals(90, RestToRestSlew.angleDegrees(x, new Trajectory.Vector(0, 1, 0)), 1e-12);
    assertEquals(180, RestToRestSlew.angleDegrees(x, new Trajectory.Vector(-1, 0, 0)), 1e-12);
  }
}
