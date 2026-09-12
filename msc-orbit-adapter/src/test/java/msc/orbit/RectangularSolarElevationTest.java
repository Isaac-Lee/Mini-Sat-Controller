package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinatesProvider;
import org.orekit.utils.TimeStampedPVCoordinates;

class RectangularSolarElevationTest {
  final OneAxisEllipsoid earth = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
      Constants.WGS84_EARTH_FLATTENING, FramesFactory.getGCRF());

  PVCoordinatesProvider provider(Vector3D position) {
    return (date, frame) -> {
      assertSame(earth.getBodyFrame(), frame, "Sun must be requested in the ellipsoid's frame");
      return new TimeStampedPVCoordinates(date, position, Vector3D.ZERO, Vector3D.ZERO);
    };
  }

  @Test
  void interiorMinimumCanBeMissedByCentreAndCorners() {
    var area = new RectangularSolarElevation.Rectangle(-30, 30, -20, 20, 0);
    var sun = new GeodeticPoint(Math.toRadians(8), Math.toRadians(12), 0).getZenith().negate();
    assertEquals(-1, RectangularSolarElevation.minimumNormalDot(area, sun), 1e-15);
    for (double[] p : new double[][] {{0, 0}, {-20, -30}, {-20, 30}, {20, -30}, {20, 30}}) {
      double dot = new GeodeticPoint(Math.toRadians(p[0]), Math.toRadians(p[1]), 0)
          .getZenith().dotProduct(sun);
      assertTrue(dot > -0.99, "Five points do not establish the continuous minimum");
    }
  }

  @Test
  void analyticMinimumAndParallaxBoundCoverDenseGeodeticGrid() {
    var random = new Random(63229);
    for (int trial = 0; trial < 24; trial++) {
      double west = -180 + random.nextDouble() * 250;
      double east = Math.min(180, west + 1 + random.nextDouble() * 100);
      double south = -89 + random.nextDouble() * 140;
      double north = Math.min(89, south + 1 + random.nextDouble() * 35);
      var area = new RectangularSolarElevation.Rectangle(west, east, south, north,
          trial % 2 == 0 ? -500 : 10000);
      var sun = new Vector3D(random.nextDouble() - .5, random.nextDouble() - .5,
          random.nextDouble() - .5).normalize();
      var position = sun.scalarMultiply(1.49e11);
      var bound = RectangularSolarElevation.at(area, earth, provider(position), AbsoluteDate.J2000_EPOCH);
      assertEquals(AbsoluteDate.J2000_EPOCH, bound.epoch());
      double minimum = RectangularSolarElevation.minimumNormalDot(area, sun);
      for (int i = 0; i <= 40; i++) for (int j = 0; j <= 40; j++) {
        var point = new GeodeticPoint(Math.toRadians(south + (north - south) * i / 40),
            Math.toRadians(west + (east - west) * j / 40), area.altitudeMeters());
        assertTrue(minimum <= point.getZenith().dotProduct(sun) + 1e-14);
        var localDirection = position.subtract(earth.transform(point)).normalize();
        double elevation = Math.asin(Math.max(-1, Math.min(1, point.getZenith().dotProduct(localDirection))));
        assertTrue(bound.lowerElevationRadians() <= elevation + 1e-12);
      }
    }
  }

  @Test
  void longitudeWrapAndPolarSunStillHaveCorrectEndpointMinimum() {
    var area = new RectangularSolarElevation.Rectangle(150, 180, -10, 30, 0);
    assertEquals(-1, RectangularSolarElevation.minimumNormalDot(area, Vector3D.PLUS_I), 1e-15);
    assertEquals(Math.sin(Math.toRadians(-10)),
        RectangularSolarElevation.minimumNormalDot(area, Vector3D.PLUS_K), 1e-15);
    assertEquals(-Math.sin(Math.toRadians(30)),
        RectangularSolarElevation.minimumNormalDot(area, Vector3D.MINUS_K), 1e-15);
  }

  @Test
  void invalidRectangleAndSunGeometryAreRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new RectangularSolarElevation.Rectangle(170, -170, -10, 10, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new RectangularSolarElevation.Rectangle(-10, 10, -90, 10, 0));
    var area = new RectangularSolarElevation.Rectangle(-10, 10, -10, 10, 0);
    assertThrows(IllegalArgumentException.class,
        () -> RectangularSolarElevation.at(area, earth, provider(Vector3D.ZERO), AbsoluteDate.J2000_EPOCH));
    assertThrows(IllegalArgumentException.class,
        () -> RectangularSolarElevation.at(area, earth, provider(Vector3D.NaN), AbsoluteDate.J2000_EPOCH));
  }
}
