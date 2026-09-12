package msc.orbit;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinatesProvider;

/**
 * Instantaneous spatial solar-elevation bound over a continuous geodetic rectangle.
 * This is a floating-point evaluation of a mathematical bound relative to the supplied solar
 * model, not an ephemeris error bound or a certificate over a time interval. A consumer must
 * separately qualify numerical/model margins and temporal coverage before asserting feasibility.
 */
public final class RectangularSolarElevation {
  private RectangularSolarElevation() {}

  /** Matches the current target-illumination AOI interface's explicit latitude support. */
  public static final double MAX_ABSOLUTE_LATITUDE_DEGREES = 89;

  /** Latitude is geodetic, longitude is east-positive, altitude is relative to the ellipsoid. */
  public record Rectangle(double westDegrees, double eastDegrees, double southDegrees,
                          double northDegrees, double altitudeMeters) {
    public Rectangle {
      for (double value : new double[] {westDegrees, eastDegrees, southDegrees, northDegrees, altitudeMeters})
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite AOI required");
      if (westDegrees < -180 || eastDegrees > 180 || westDegrees >= eastDegrees
          || southDegrees < -MAX_ABSOLUTE_LATITUDE_DEGREES
          || northDegrees > MAX_ABSOLUTE_LATITUDE_DEGREES || southDegrees >= northDegrees
          || altitudeMeters < -500 || altitudeMeters > 10000)
        throw new IllegalArgumentException("Nonwrapping geodetic rectangle outside model bounds");
    }
  }

  /** Applies only at epoch; it contains no assertion about an interval or terrain elevation range. */
  public record Bound(AbsoluteDate epoch, double minimumGeocentricElevationRadians, double parallaxBoundRadians,
                      double lowerElevationRadians) {}

  public static Bound at(Rectangle area, OneAxisEllipsoid earth,
                         PVCoordinatesProvider sun, AbsoluteDate date) {
    // Obtain the Sun in exactly the frame used by the geodetic Earth model; TEME/EME2000
    // components must never be mistaken for Earth-fixed components.
    var position = sun.getPosition(date, earth.getBodyFrame());
    double radius = earth.getEquatorialRadius();
    double flattening = earth.getFlattening();
    if (!Double.isFinite(radius) || radius <= 0 || !Double.isFinite(flattening)
        || flattening < 0 || flattening >= 1)
      throw new IllegalArgumentException("An oblate Earth model is required");
    // Geodetic surface normals do not depend on flattening. For an oblate ellipsoid, the
    // equatorial radius bounds surface position norms. The triangle inequality also covers
    // negative altitude; using radius + altitude would not.
    double maximumRadius = radius + Math.abs(area.altitudeMeters());
    double distance = position.getNorm();
    if (!Double.isFinite(distance) || distance <= maximumRadius)
      throw new IllegalArgumentException("Sun must be outside the enclosing Earth sphere");
    double minimumDot = minimumNormalDot(area, position.normalize());
    double geocentric = Math.asin(Math.max(-1, Math.min(1, minimumDot)));
    double parallax = Math.asin(maximumRadius / distance);
    return new Bound(date, geocentric, parallax, Math.max(-Math.PI / 2, geocentric - parallax));
  }

  static double minimumNormalDot(Rectangle area, Vector3D unitSun) {
    double west = Math.toRadians(area.westDegrees());
    double east = Math.toRadians(area.eastDegrees());
    double south = Math.toRadians(area.southDegrees());
    double north = Math.toRadians(area.northDegrees());
    double x = unitSun.getX(), y = unitSun.getY(), z = unitSun.getZ();
    // cos(latitude) >= 0, so minimizing longitude first is valid for every latitude.
    double horizontal = Math.min(x * Math.cos(west) + y * Math.sin(west),
        x * Math.cos(east) + y * Math.sin(east));
    double antiSolar = Math.atan2(y, x) + Math.PI;
    for (int turn = -1; turn <= 1; turn++) {
      double longitude = antiSolar + turn * 2 * Math.PI;
      if (longitude >= west && longitude <= east)
        horizontal = Math.min(horizontal, -Math.hypot(x, y));
    }
    double minimum = Math.min(value(horizontal, z, south), value(horizontal, z, north));
    double stationary = Math.atan2(z, horizontal);
    // Evaluate all stationary points in the latitude interval, whether maxima or minima.
    for (int halfTurn = -2; halfTurn <= 2; halfTurn++) {
      double latitude = stationary + halfTurn * Math.PI;
      if (latitude >= south && latitude <= north)
        minimum = Math.min(minimum, value(horizontal, z, latitude));
    }
    return minimum;
  }

  private static double value(double horizontal, double vertical, double latitude) {
    return horizontal * Math.cos(latitude) + vertical * Math.sin(latitude);
  }
}
