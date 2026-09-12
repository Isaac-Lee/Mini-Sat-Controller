package msc.orbit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory.Vector;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.utils.Constants;

/**
 * Synthetic rectilinear camera projected onto the target's WGS84 geodetic tangent plane. Across x
 * along = boresight; along is geodetic north projected perpendicular to boresight. Pixel boundaries
 * are uniformly spaced on the camera's focal plane, not in angle or on Earth. This instantaneous
 * planar model does not establish terrain coverage or interval feasibility.
 */
public final class StareCameraProjection {
  public static final String MODEL = "STARE_TARGET_TANGENT_PLANE_V1";
  public static final double MINIMUM_NORTH_AXIS_SEPARATION_DEGREES = 1;

  public record Camera(
      double halfAngleAcrossDegrees,
      double halfAngleAlongDegrees,
      int rasterColumns,
      int rasterRows) {
    public Camera {
      if (!Double.isFinite(halfAngleAcrossDegrees)
          || halfAngleAcrossDegrees <= 0
          || halfAngleAcrossDegrees >= 90
          || !Double.isFinite(halfAngleAlongDegrees)
          || halfAngleAlongDegrees <= 0
          || halfAngleAlongDegrees >= 90)
        throw new IllegalArgumentException("Camera half angles must be finite and >0..<90 degrees");
      if (rasterColumns < 1
          || rasterColumns > 1_000_000
          || rasterRows < 1
          || rasterRows > 1_000_000)
        throw new IllegalArgumentException("Raster dimensions must be 1..1000000");
    }
  }

  public record Point(double eastMeters, double northMeters) {}

  /** Maximum spacing bound applies to either adjacent-pixel axis, everywhere in this image. */
  public record Footprint(
      List<Point> corners, double areaSquareMeters, double maximumPixelAxisSpacingBoundMeters) {
    public Footprint {
      corners = List.copyOf(corners);
      if (!Double.isFinite(areaSquareMeters)
          || areaSquareMeters <= 0
          || !Double.isFinite(maximumPixelAxisSpacingBoundMeters)
          || maximumPixelAxisSpacingBoundMeters <= 0)
        throw new IllegalArgumentException(
            "Finite positive footprint area and spacing are required");
    }
  }

  /** A rectangle in planar metres, not a bounding box of transformed geographic corners. */
  public record Rectangle(
      double westMeters, double eastMeters, double southMeters, double northMeters) {
    public Rectangle {
      if (!Double.isFinite(westMeters)
          || !Double.isFinite(eastMeters)
          || !Double.isFinite(southMeters)
          || !Double.isFinite(northMeters)
          || westMeters >= eastMeters
          || southMeters >= northMeters)
        throw new IllegalArgumentException(
            "A finite, nonempty tangent-plane rectangle is required");
      double area = (eastMeters - westMeters) * (northMeters - southMeters);
      if (!Double.isFinite(area) || area <= 0)
        throw new IllegalArgumentException("Rectangle area must be finite and positive");
    }
  }

  private final Camera camera;
  private final Vector3D satellite, normal, north, east, boresight, along, across;
  private final double height, halfWidth, halfHeight, minimumDenominator;

  public StareCameraProjection(Camera camera, Vector satelliteEarthFixedMeters, Target target) {
    this.camera = Objects.requireNonNull(camera);
    Objects.requireNonNull(satelliteEarthFixedMeters);
    Objects.requireNonNull(target);
    double latitude = Math.toRadians(target.latitudeDegrees());
    double longitude = Math.toRadians(target.longitudeDegrees());
    normal =
        new Vector3D(
            Math.cos(latitude) * Math.cos(longitude),
            Math.cos(latitude) * Math.sin(longitude),
            Math.sin(latitude));
    north =
        new Vector3D(
            -Math.sin(latitude) * Math.cos(longitude),
            -Math.sin(latitude) * Math.sin(longitude),
            Math.cos(latitude));
    east = new Vector3D(-Math.sin(longitude), Math.cos(longitude), 0);
    double flattening = Constants.WGS84_EARTH_FLATTENING;
    double eccentricitySquared = flattening * (2 - flattening);
    double radius =
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS
            / Math.sqrt(1 - eccentricitySquared * Math.sin(latitude) * Math.sin(latitude));
    double altitude = target.altitudeMeters();
    var targetPosition =
        new Vector3D(
            (radius + altitude) * Math.cos(latitude) * Math.cos(longitude),
            (radius + altitude) * Math.cos(latitude) * Math.sin(longitude),
            (radius * (1 - eccentricitySquared) + altitude) * Math.sin(latitude));
    satellite =
        new Vector3D(
                satelliteEarthFixedMeters.x(),
                satelliteEarthFixedMeters.y(),
                satelliteEarthFixedMeters.z())
            .subtract(targetPosition);
    height = satellite.dotProduct(normal);
    if (!Double.isFinite(height) || height <= 0 || !Double.isFinite(satellite.getNorm()))
      throw new IllegalArgumentException("Satellite must be above the target tangent plane");
    boresight = satellite.negate().normalize();
    var projectedNorth = north.subtract(boresight.scalarMultiply(north.dotProduct(boresight)));
    if (projectedNorth.getNorm() < Math.sin(Math.toRadians(MINIMUM_NORTH_AXIS_SEPARATION_DEGREES)))
      throw new IllegalArgumentException("Projected north orientation is degenerate");
    along = projectedNorth.normalize();
    across = along.crossProduct(boresight).normalize();
    halfWidth = Math.tan(Math.toRadians(camera.halfAngleAcrossDegrees()));
    halfHeight = Math.tan(Math.toRadians(camera.halfAngleAlongDegrees()));
    // The normal component is affine in focal-plane x/y. Its extrema occur at the corners.
    minimumDenominator =
        -boresight.dotProduct(normal)
            - halfWidth * Math.abs(across.dotProduct(normal))
            - halfHeight * Math.abs(along.dotProduct(normal));
    if (minimumDenominator <= 1e-9)
      throw new IllegalArgumentException("Camera rays cross or approach the tangent-plane horizon");
  }

  public Vector acrossUnit() {
    return vector(across);
  }

  public Vector alongUnit() {
    return vector(along);
  }

  public Vector boresightUnit() {
    return vector(boresight);
  }

  public Point pixelBoundary(double column, double row) {
    if (!Double.isFinite(column)
        || !Double.isFinite(row)
        || column < 0
        || row < 0
        || column > camera.rasterColumns()
        || row > camera.rasterRows())
      throw new IllegalArgumentException("Pixel boundary lies outside the raster");
    return project(
        halfWidth * (2 * column / camera.rasterColumns() - 1),
        halfHeight * (2 * row / camera.rasterRows() - 1));
  }

  public Footprint footprint() {
    var corners =
        List.of(
            project(-halfWidth, -halfHeight),
            project(halfWidth, -halfHeight),
            project(halfWidth, halfHeight),
            project(-halfWidth, halfHeight));
    double acrossBound =
        derivativeBound(across, along, halfHeight) * 2 * halfWidth / camera.rasterColumns();
    double alongBound =
        derivativeBound(along, across, halfWidth) * 2 * halfHeight / camera.rasterRows();
    return new Footprint(corners, area(corners), Math.max(acrossBound, alongBound));
  }

  /** Area fraction within a rectangle already defined in this same tangent-plane chart. */
  public double coverageFraction(Rectangle rectangle) {
    Objects.requireNonNull(rectangle);
    var polygon = footprint().corners();
    polygon = clip(polygon, 0, rectangle.westMeters(), true);
    polygon = clip(polygon, 0, rectangle.eastMeters(), false);
    polygon = clip(polygon, 1, rectangle.southMeters(), true);
    polygon = clip(polygon, 1, rectangle.northMeters(), false);
    double rectangleArea =
        (rectangle.eastMeters() - rectangle.westMeters())
            * (rectangle.northMeters() - rectangle.southMeters());
    if (!Double.isFinite(rectangleArea))
      throw new IllegalArgumentException("Rectangle area overflow");
    return Math.max(0, Math.min(1, area(polygon) / rectangleArea));
  }

  private Vector3D ray(double x, double y) {
    return boresight.add(across.scalarMultiply(x)).add(along.scalarMultiply(y));
  }

  private Point project(double x, double y) {
    var ray = ray(x, y);
    var position = satellite.add(ray.scalarMultiply(-height / ray.dotProduct(normal)));
    var point = new Point(position.dotProduct(east), position.dotProduct(north));
    if (!Double.isFinite(point.eastMeters()) || !Double.isFinite(point.northMeters()))
      throw new IllegalArgumentException("Projected point overflow");
    return point;
  }

  private double derivativeBound(Vector3D axis, Vector3D other, double otherHalfExtent) {
    double maximumNumerator = 0;
    for (double extent : new double[] {-otherHalfExtent, otherHalfExtent}) {
      var direction = boresight.add(other.scalarMultiply(extent));
      var numerator =
          axis.scalarMultiply(direction.dotProduct(normal))
              .subtract(direction.scalarMultiply(axis.dotProduct(normal)));
      maximumNumerator = Math.max(maximumNumerator, numerator.getNorm());
    }
    // d(S-h*d/(n.d))/dx = -h*(a*(n.d)-d*(n.a))/(n.d)^2.
    // Numerator norm is convex in the other coordinate; denominator has the corner bound above.
    return height * maximumNumerator / (minimumDenominator * minimumDenominator);
  }

  private static List<Point> clip(List<Point> input, int axis, double bound, boolean lower) {
    if (input.isEmpty()) return input;
    var output = new ArrayList<Point>();
    Point previous = input.getLast();
    double previousValue = coordinate(previous, axis);
    boolean previousInside = lower ? previousValue >= bound : previousValue <= bound;
    for (Point current : input) {
      double currentValue = coordinate(current, axis);
      boolean currentInside = lower ? currentValue >= bound : currentValue <= bound;
      if (currentInside != previousInside) {
        double fraction = (bound - previousValue) / (currentValue - previousValue);
        output.add(
            new Point(
                previous.eastMeters() + fraction * (current.eastMeters() - previous.eastMeters()),
                previous.northMeters()
                    + fraction * (current.northMeters() - previous.northMeters())));
      }
      if (currentInside) output.add(current);
      previous = current;
      previousValue = currentValue;
      previousInside = currentInside;
    }
    return output;
  }

  private static double coordinate(Point point, int axis) {
    return axis == 0 ? point.eastMeters() : point.northMeters();
  }

  private static double area(List<Point> polygon) {
    double twiceArea = 0;
    for (int i = 0; i < polygon.size(); i++) {
      Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
      twiceArea += a.eastMeters() * b.northMeters() - a.northMeters() * b.eastMeters();
    }
    return Math.abs(twiceArea) / 2;
  }

  private static Vector vector(Vector3D value) {
    return new Vector(value.getX(), value.getY(), value.getZ());
  }
}
