package msc.domain.flightdynamics;

/**
 * Kinematic rest-to-rest single-axis slew time. Angle in degrees, rate in deg/s, acceleration in
 * deg/s², times in seconds. The profile accelerates at the maximum acceleration, optionally coasts
 * at the maximum rate, then decelerates symmetrically to rest; settling is a fixed additive time.
 * No inertia, torque, momentum, flexible-mode, sensor or controller model is involved, so the
 * result is a bound for planning arithmetic, not a predicted attitude history.
 */
public final class RestToRestSlew {
  private RestToRestSlew() {}

  public enum Shape {
    NONE,
    TRIANGULAR,
    TRAPEZOIDAL
  }

  /**
   * {@code slewSeconds} excludes settling; {@code totalSeconds} adds it. A zero angle needs no
   * motion and therefore no settling.
   */
  public record Profile(
      Shape shape,
      double angleDegrees,
      double peakRateDegreesPerSecond,
      double slewSeconds,
      double settlingSeconds,
      double totalSeconds) {}

  public static Profile profile(
      double angleDegrees,
      double maximumRateDegreesPerSecond,
      double maximumAccelerationDegreesPerSecondSquared,
      double settlingSeconds) {
    if (!Double.isFinite(angleDegrees) || angleDegrees < 0 || angleDegrees > 180)
      throw new IllegalArgumentException("Slew angle must be 0..180 degrees");
    if (!Double.isFinite(maximumRateDegreesPerSecond) || maximumRateDegreesPerSecond <= 0)
      throw new IllegalArgumentException("Maximum rate must be positive");
    if (!Double.isFinite(maximumAccelerationDegreesPerSecondSquared)
        || maximumAccelerationDegreesPerSecondSquared <= 0)
      throw new IllegalArgumentException("Maximum acceleration must be positive");
    if (!Double.isFinite(settlingSeconds) || settlingSeconds < 0)
      throw new IllegalArgumentException("Settling time must be non-negative");
    if (angleDegrees == 0) return new Profile(Shape.NONE, 0, 0, 0, 0, 0);
    double rate = maximumRateDegreesPerSecond;
    double acceleration = maximumAccelerationDegreesPerSecondSquared;
    // The rate limit is just reached when the angle covered while accelerating then decelerating
    // equals rate²/acceleration; at exactly that angle both shapes give the same time.
    Shape shape;
    double peak;
    double slew;
    if (angleDegrees <= rate * rate / acceleration) {
      shape = Shape.TRIANGULAR;
      peak = Math.sqrt(angleDegrees * acceleration);
      slew = 2 * Math.sqrt(angleDegrees / acceleration);
    } else {
      shape = Shape.TRAPEZOIDAL;
      peak = rate;
      slew = angleDegrees / rate + rate / acceleration;
    }
    return new Profile(shape, angleDegrees, peak, slew, settlingSeconds, slew + settlingSeconds);
  }

  /** Angle between two direction vectors in degrees, robust near 0 and 180 degrees. */
  public static double angleDegrees(Trajectory.Vector a, Trajectory.Vector b) {
    double cx = a.y() * b.z() - a.z() * b.y();
    double cy = a.z() * b.x() - a.x() * b.z();
    double cz = a.x() * b.y() - a.y() * b.x();
    double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    return Math.toDegrees(Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), dot));
  }
}
