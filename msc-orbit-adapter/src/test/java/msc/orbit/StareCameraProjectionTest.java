package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory.Vector;
import msc.orbit.StareCameraProjection.Camera;
import msc.orbit.StareCameraProjection.Point;
import msc.orbit.StareCameraProjection.Rectangle;
import org.junit.jupiter.api.Test;

class StareCameraProjectionTest {
  private static final Target TARGET = new Target("synthetic-equator", 0, 0, 0);
  private static final double HEIGHT = 500_000;

  private StareCameraProjection nadir(double height) {
    return new StareCameraProjection(
        new Camera(1, 2, 100, 200), new Vector(6378137 + height, 0, 0), TARGET);
  }

  @Test
  void nadirFootprintAndPixelScaleMatchHandCalculation() {
    var projection = nadir(HEIGHT);
    var footprint = projection.footprint();
    double halfWidth = HEIGHT * Math.tan(Math.toRadians(1));
    double halfHeight = HEIGHT * Math.tan(Math.toRadians(2));
    assertEquals(4 * halfWidth * halfHeight, footprint.areaSquareMeters(), 1e-5);
    assertEquals(new Point(0, 0), projection.pixelBoundary(50, 100));
    assertEquals(halfWidth, footprint.corners().getFirst().eastMeters(), 1e-8);
    assertEquals(-halfHeight, footprint.corners().getFirst().northMeters(), 1e-8);
    assertEquals(
        Math.max(2 * halfWidth / 100, 2 * halfHeight / 200),
        footprint.maximumPixelAxisSpacingBoundMeters(),
        1e-8);
    assertEquals(-1, projection.boresightUnit().x(), 1e-12);
    assertEquals(0, projection.boresightUnit().y(), 1e-12);
    assertEquals(0, projection.boresightUnit().z(), 1e-12);
    assertEquals(-1, projection.acrossUnit().y(), 1e-12);
    assertEquals(1, projection.alongUnit().z(), 1e-12);
    assertEquals(
        4 * footprint.areaSquareMeters(), nadir(2 * HEIGHT).footprint().areaSquareMeters(), 1e-4);
    assertEquals(
        2 * footprint.maximumPixelAxisSpacingBoundMeters(),
        nadir(2 * HEIGHT).footprint().maximumPixelAxisSpacingBoundMeters(),
        1e-8);
  }

  @Test
  void rectangleClippingHandlesFullPartialEmptyAndBoundaryContact() {
    var projection = nadir(HEIGHT);
    double width = HEIGHT * Math.tan(Math.toRadians(1));
    double height = HEIGHT * Math.tan(Math.toRadians(2));
    assertEquals(1, projection.coverageFraction(new Rectangle(-100, 100, -100, 100)), 1e-12);
    assertEquals(
        .25,
        projection.coverageFraction(new Rectangle(-2 * width, 2 * width, -2 * height, 2 * height)),
        1e-12);
    assertEquals(
        .5, projection.coverageFraction(new Rectangle(0, 2 * width, -height, height)), 1e-12);
    assertEquals(
        0, projection.coverageFraction(new Rectangle(3 * width, 4 * width, -height, height)));
    assertEquals(
        0, projection.coverageFraction(new Rectangle(width, 2 * width, -height, height)), 1e-12);
  }

  @Test
  void obliqueProjectionMatchesIndependentRayIntersectionsAndBoundsEveryPixelEdge() {
    int columns = 32, rows = 24;
    var camera = new Camera(4, 3, columns, rows);
    double eastOffset = 300_000;
    var projection =
        new StareCameraProjection(camera, new Vector(6378137 + HEIGHT, eastOffset, 0), TARGET);
    var footprint = projection.footprint();
    double length = Math.hypot(HEIGHT, eastOffset);
    // With north +Z, b=(-H,-E,0)/L and across=(E,-H,0)/L.
    double maximumObserved = 0;
    for (int row = 0; row <= rows; row++) {
      for (int column = 0; column <= columns; column++) {
        double x = Math.tan(Math.toRadians(4)) * (2.0 * column / columns - 1);
        double y = Math.tan(Math.toRadians(3)) * (2.0 * row / rows - 1);
        double dx = (-HEIGHT + x * eastOffset) / length;
        double dy = (-eastOffset - x * HEIGHT) / length;
        double parameter = -HEIGHT / dx;
        var point = projection.pixelBoundary(column, row);
        assertEquals(eastOffset + parameter * dy, point.eastMeters(), 1e-7);
        assertEquals(parameter * y, point.northMeters(), 1e-7);
        if (column < columns)
          maximumObserved =
              Math.max(maximumObserved, distance(point, projection.pixelBoundary(column + 1, row)));
        if (row < rows)
          maximumObserved =
              Math.max(maximumObserved, distance(point, projection.pixelBoundary(column, row + 1)));
      }
    }
    assertTrue(maximumObserved <= footprint.maximumPixelAxisSpacingBoundMeters() * (1 + 1e-12));
    assertTrue(
        maximumObserved > .8 * footprint.maximumPixelAxisSpacingBoundMeters(),
        "Bound should remain useful in the synthetic oblique fixture");
  }

  @Test
  void midLatitudeUsesGeodeticNormalInsteadOfEarthRadius() {
    double latitude = Math.toRadians(45), longitude = Math.toRadians(30);
    double f = 1 / 298.257223563, e2 = f * (2 - f);
    double radius = 6378137 / Math.sqrt(1 - e2 * Math.sin(latitude) * Math.sin(latitude));
    var satellite =
        new Vector(
            (radius + HEIGHT) * Math.cos(latitude) * Math.cos(longitude),
            (radius + HEIGHT) * Math.cos(latitude) * Math.sin(longitude),
            (radius * (1 - e2) + HEIGHT) * Math.sin(latitude));
    var projection =
        new StareCameraProjection(
            new Camera(1, 2, 100, 200), satellite, new Target("synthetic-mid-latitude", 45, 30, 0));
    assertEquals(
        nadir(HEIGHT).footprint().areaSquareMeters(),
        projection.footprint().areaSquareMeters(),
        .001);
    assertEquals(0, projection.pixelBoundary(50, 100).eastMeters(), 1e-7);
    assertEquals(0, projection.pixelBoundary(50, 100).northMeters(), 1e-7);
  }

  @Test
  void invalidCamerasHiddenTargetsAndHorizonCrossingAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Camera(Double.NaN, 1, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new Camera(1, 90, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new Camera(1, 1, 0, 10));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StareCameraProjection(
                new Camera(1, 1, 10, 10), new Vector(-7_000_000, 0, 0), TARGET));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StareCameraProjection(
                new Camera(30, 30, 10, 10), new Vector(6378137 + 1000, 500_000, 0), TARGET));
    assertThrows(IllegalArgumentException.class, () -> nadir(HEIGHT).pixelBoundary(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new Rectangle(0, 1e-200, 0, 1e-200));
  }

  private static double distance(Point a, Point b) {
    return Math.hypot(a.eastMeters() - b.eastMeters(), a.northMeters() - b.northMeters());
  }

  @Test
  void northAxisDegeneracyThresholdIsSymmetricForBothDirections() {
    var camera = new Camera(.01, .01, 10, 10);
    for (int sign : new int[] {-1, 1}) {
      var tooClose = new Vector(6378137 + 1000, 0, sign * 1000 / Math.tan(Math.toRadians(.5)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new StareCameraProjection(camera, tooClose, TARGET));
      var separated = new Vector(6378137 + 1000, 0, sign * 1000 / Math.tan(Math.toRadians(1.5)));
      assertTrue(
          new StareCameraProjection(camera, separated, TARGET).footprint().areaSquareMeters() > 0);
    }
  }
}
