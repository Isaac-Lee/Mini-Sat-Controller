package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;

/** Numerical comparisons against separately constructed Orekit propagators and geometry. */
class RequiredTargetPointingCalculatorIT {
  private static final double MU = 3.986004418e14;

  private OrekitReferenceFrames frames() throws Exception {
    return new OrekitReferenceFrames(
        Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")), System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  private InitialState circularEquatorialOrbit(OrekitReferenceFrames f) {
    return new InitialState(
        "sim-pointing-v1",
        "sim-1",
        f.fromUtc("2026-09-01T12:00:00"),
        new Vector(7_000_000, 0, 0),
        new Vector(0, Math.sqrt(MU / 7_000_000), 0),
        "Synthetic circular geometry fixture");
  }

  /** Same SPACEEYE-T1 / NORAD 63229 fixture already pinned in {@code GeneralPerturbationsIT}. */
  private MeanElements spaceeye(int noradId) {
    return new MeanElements(
        noradId,
        "SPACEEYE-T1",
        "2025-052V",
        "2026-09-11T03:28:43.405248",
        15.23132288,
        .00040966,
        97.3818,
        145.2884,
        187.0783,
        173.0397,
        .00013731904,
        3.172e-5,
        0,
        999,
        8292,
        "U");
  }

  private double distance(Vector a, Vector b) {
    double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  @Test
  void topocentricAzimuthUsesNorthThenEastAndElevationIsPositiveAboveHorizon() throws Exception {
    var f = frames();
    var epoch = f.fromUtc("2026-09-01T12:00:00");
    var date = new KeplerianOrbitAdapter().date(epoch);
    var target = new Target("equator-prime-meridian", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(1_000_000_000L)));
    for (int direction = 0; direction < 2; direction++) {
      // At this target local up is +X, east is +Y and north is +Z.
      var position =
          new Vector3D(6378137 + 500000, direction == 1 ? 100000 : 0, direction == 0 ? 100000 : 0);
      var earthPv = new PVCoordinates(position, new Vector3D(0, 7500, 0));
      var inertialPv =
          f.earth()
              .getBodyFrame()
              .getTransformTo(f.context().getFrames().getEME2000(), date)
              .transformPVCoordinates(earthPv);
      var initial =
          new InitialState(
              "cardinal-" + direction,
              "sim-cardinal",
              epoch,
              KeplerianOrbitAdapter.vector(inertialPv.getPosition()),
              KeplerianOrbitAdapter.vector(inertialPv.getVelocity()),
              "Synthetic cardinal geometry");
      var sample =
          new RequiredTargetPointingCalculator(f)
              .profile(initial, target, horizon, 1, 0)
              .samples()
              .getFirst();
      double expectedAzimuth = direction * 90;
      double azimuthError =
          (sample.topocentricAzimuthDegrees() - expectedAzimuth + 540) % 360 - 180;
      assertEquals(0, azimuthError, 1e-7);
      assertEquals(
          Math.toDegrees(Math.atan2(500000, 100000)), sample.topocentricElevationDegrees(), 1e-7);
      assertTrue(sample.targetVisible());
    }
  }

  // G1: production off-nadir/slant-range agree with an independently constructed
  // CartesianOrbit+KeplerianPropagator and a freshly transformed target position, at every
  // sample. G2: the Earth-fixed and inertial LOS unit vectors are NOT the same vector at any
  // sample away from a coincidental alignment -- proving the two frames are genuinely
  // distinguished, not silently aliased (the exact risk called out in the class javadoc).
  @Test
  void cartesianProfileAgreesWithIndependentGeometryAndDistinguishesFrames() throws Exception {
    var f = frames();
    var initial = circularEquatorialOrbit(f);
    var groundAtEpoch =
        f.groundPoint(
            new Sample(
                initial.epoch(), initial.positionMeters(), initial.velocityMetersPerSecond()));
    var target =
        new Target(
            "under-sat", groundAtEpoch.latitudeDegrees(), groundAtEpoch.longitudeDegrees(), 0);
    var horizon =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(600_000_000_000L)));
    int stepSeconds = 60;

    var calculator = new RequiredTargetPointingCalculator(f);
    var profile = calculator.profile(initial, target, horizon, stepSeconds, 0);

    assertEquals(RequiredTargetPointingCalculator.POINTING_MODEL, profile.pointingModel());
    assertEquals(10, profile.samples().size());

    var time = new KeplerianOrbitAdapter();
    var independentOrbit =
        new CartesianOrbit(
            new PVCoordinates(
                KeplerianOrbitAdapter.vector(initial.positionMeters()),
                KeplerianOrbitAdapter.vector(initial.velocityMetersPerSecond())),
            f.context().getFrames().getEME2000(),
            time.date(initial.epoch()),
            Constants.WGS84_EARTH_MU);
    var independentPropagator = new KeplerianPropagator(independentOrbit);
    var independentEarth = f.earth();
    var geodetic =
        new GeodeticPoint(
            Math.toRadians(target.latitudeDegrees()), Math.toRadians(target.longitudeDegrees()), 0);
    var targetEarthFixed = independentEarth.transform(geodetic);

    int frameDifferenceCount = 0;
    for (var sample : profile.samples()) {
      var date = time.date(sample.instant());
      var state = independentPropagator.propagate(date);
      var positionEarthFixed = state.getPosition(independentEarth.getBodyFrame());
      var independentLos = targetEarthFixed.subtract(positionEarthFixed);
      double independentOffNadirDegrees =
          Math.toDegrees(Vector3D.angle(positionEarthFixed.negate(), independentLos));
      double independentSlantRangeMeters = independentLos.getNorm();

      assertEquals(
          independentOffNadirDegrees,
          sample.requiredOffNadirDegrees(),
          1e-6,
          "off-nadir must agree with an independently built reference at " + sample.instant());
      assertEquals(
          independentSlantRangeMeters,
          sample.slantRangeMeters(),
          1e-3,
          "slant range must agree with an independently built reference at " + sample.instant());

      // Frame identity: prove the Earth-fixed and inertial LOS unit vectors are not the same
      // vector reused under two names.
      if (distance(sample.lineOfSightEarthFixedUnit(), sample.lineOfSightInertialUnit()) > 1e-6)
        frameDifferenceCount++;
    }
    assertTrue(
        frameDifferenceCount > 0,
        "Earth-fixed and inertial LOS must actually differ at some sample, proving the two frames"
            + " are not silently aliased");
  }

  // At epoch the antipodal target lies along the nadir ray through Earth's centre:
  // off-nadir is near zero while visibility is false. Angle alone cannot prove access.
  @Test
  void farSideTargetIsRecordedNotRejectedAndHasSmallNotLargeOffNadir() throws Exception {
    var f = frames();
    var initial = circularEquatorialOrbit(f);
    var groundAtEpoch =
        f.groundPoint(
            new Sample(
                initial.epoch(), initial.positionMeters(), initial.velocityMetersPerSecond()));
    var opposite =
        new Target(
            "opposite",
            -groundAtEpoch.latitudeDegrees(),
            Math.IEEEremainder(groundAtEpoch.longitudeDegrees() + 180, 360),
            0);
    // Exactly one sample, at epoch, where the antipodal construction is exact.
    var horizon =
        new TimeWindow(initial.epoch(), initial.epoch().plus(new MissionDuration(60_000_000_000L)));
    var calculator = new RequiredTargetPointingCalculator(f);

    var profile = calculator.profile(initial, opposite, horizon, 60, 0);
    assertEquals(1, profile.samples().size());
    var sample = profile.samples().getFirst();
    assertFalse(sample.targetVisible(), "an antipodal target must not be reported visible");
    assertEquals(
        0.0,
        sample.requiredOffNadirDegrees(),
        1.0,
        "an antipodal target lies along the extended nadir ray, so its required off-nadir angle"
            + " must be small, not large -- off-nadir alone does not prove visibility");
  }

  @Test
  void sampleCapAndHorizonBoundsAreEnforced() throws Exception {
    var f = frames();
    var initial = circularEquatorialOrbit(f);
    var target = new Target("t", 0, 0, 0);
    var calculator = new RequiredTargetPointingCalculator(f);

    // Horizon exceeds the 7-day model-validity/implementation bound.
    var tooLong =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(8L * 86400_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class, () -> calculator.profile(initial, target, tooLong, 60, 0));

    // Within 7 days, but step=1s over 25000s exceeds the 20000-sample cap.
    var tooManySamples =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(25_000_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.profile(initial, target, tooManySamples, 1, 0));

    // Step out of the 1..3600 second range.
    var shortHorizon =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(3600_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.profile(initial, target, shortHorizon, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.profile(initial, target, shortHorizon, 3601, 0));

    // Horizon far outside the initial orbit's +/-7-day epoch validity window.
    var farStart = initial.epoch().plus(new MissionDuration(8L * 86400_000_000_000L));
    var farHorizon =
        new TimeWindow(farStart, farStart.plus(new MissionDuration(3600_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.profile(initial, target, farHorizon, 60, 0));
  }

  // G-GP: the public-GP path uses the real SGP4/SDP4 TLEPropagator directly (never a two-body
  // conversion), verified against an independently constructed TLE + TLEPropagator built from
  // nothing but the same raw elements, mirroring GeneralPerturbationsEclipseIT's technique.
  @Test
  void publicGpProfileAgreesWithIndependentSgp4Geometry() throws Exception {
    var f = frames();
    var elements = spaceeye(63229);
    var gpAdapter = new GeneralPerturbationsAdapter(f);
    var epoch = gpAdapter.epoch(elements);
    var rawTle = gpAdapter.tle(elements);

    // Ground point at epoch, using the real production propagator only to pick a plausible
    // sub-satellite target -- not part of the comparison itself.
    var epochPropagator =
        TLEPropagator.selectExtrapolator(rawTle, f.context().getFrames().getTEME());
    var time = new KeplerianOrbitAdapter();
    var stateAtEpoch = epochPropagator.propagate(time.date(epoch));
    var pvAtEpoch = stateAtEpoch.getPVCoordinates(f.context().getFrames().getEME2000());
    var groundAtEpoch =
        f.groundPoint(
            new Sample(
                epoch,
                KeplerianOrbitAdapter.vector(pvAtEpoch.getPosition()),
                KeplerianOrbitAdapter.vector(pvAtEpoch.getVelocity())));
    var target =
        new Target(
            "gp-under-sat", groundAtEpoch.latitudeDegrees(), groundAtEpoch.longitudeDegrees(), 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(1800_000_000_000L)));

    var calculator = new RequiredTargetPointingCalculator(f);
    var profile = calculator.profile(elements, target, horizon, 60, 0);
    assertEquals(30, profile.samples().size());

    // Numeric constructor retains the GP fixture precision. Traditional two-line text rounds
    // eccentricity and BSTAR and therefore cannot be a sub-microdegree reference for this path.
    var independentTle =
        new TLE(
            63229,
            'U',
            2025,
            52,
            "V",
            0,
            999,
            new org.orekit.time.AbsoluteDate(
                "2026-09-11T03:28:43.405248", f.context().getTimeScales().getUTC()),
            15.23132288 * 2 * Math.PI / 86400,
            3.172e-5 * 4 * Math.PI / (86400.0 * 86400),
            0,
            .00040966,
            Math.toRadians(97.3818),
            Math.toRadians(187.0783),
            Math.toRadians(145.2884),
            Math.toRadians(173.0397),
            8292,
            .00013731904,
            f.context().getTimeScales().getUTC());
    var independentPropagator =
        TLEPropagator.selectExtrapolator(independentTle, f.context().getFrames().getTEME());
    var earth = f.earth();
    var geodetic =
        new GeodeticPoint(
            Math.toRadians(target.latitudeDegrees()), Math.toRadians(target.longitudeDegrees()), 0);
    var targetEarthFixed = earth.transform(geodetic);

    for (var sample : profile.samples()) {
      var date = time.date(sample.instant());
      var state = independentPropagator.propagate(date);
      var positionEarthFixed = state.getPosition(earth.getBodyFrame());
      double independentOffNadirDegrees =
          Math.toDegrees(
              Vector3D.angle(
                  positionEarthFixed.negate(), targetEarthFixed.subtract(positionEarthFixed)));
      assertEquals(
          independentOffNadirDegrees,
          sample.requiredOffNadirDegrees(),
          1e-6,
          "GP off-nadir must agree with an independently built SGP4/SDP4 reference at "
              + sample.instant());
      var expectedLos = targetEarthFixed.subtract(positionEarthFixed);
      assertEquals(expectedLos.getNorm(), sample.slantRangeMeters(), 1e-3);
      var unit = expectedLos.normalize();
      assertEquals(unit.getX(), sample.lineOfSightEarthFixedUnit().x(), 1e-10);
      assertEquals(unit.getY(), sample.lineOfSightEarthFixedUnit().y(), 1e-10);
      assertEquals(unit.getZ(), sample.lineOfSightEarthFixedUnit().z(), 1e-10);
      var inertialUnit =
          earth
              .getBodyFrame()
              .getTransformTo(f.context().getFrames().getEME2000(), date)
              .transformVector(unit);
      assertEquals(inertialUnit.getX(), sample.lineOfSightInertialUnit().x(), 1e-10);
      assertEquals(inertialUnit.getY(), sample.lineOfSightInertialUnit().y(), 1e-10);
      assertEquals(inertialUnit.getZ(), sample.lineOfSightInertialUnit().z(), 1e-10);
    }
  }

  // G4-equivalent: epoch/horizon validity guards apply identically to the GP path.
  @Test
  void gpEpochAndHorizonValidityGuardsApply() throws Exception {
    var f = frames();
    var elements = spaceeye(63229);
    var gpAdapter = new GeneralPerturbationsAdapter(f);
    var epoch = gpAdapter.epoch(elements);
    var calculator = new RequiredTargetPointingCalculator(f);
    var target = new Target("t", 0, 0, 0);

    var tooLong = new TimeWindow(epoch, epoch.plus(new MissionDuration(8L * 86400_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class, () -> calculator.profile(elements, target, tooLong, 60, 0));

    var farStart = epoch.plus(new MissionDuration(8L * 86400_000_000_000L));
    var farHorizon =
        new TimeWindow(farStart, farStart.plus(new MissionDuration(3600_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.profile(elements, target, farHorizon, 60, 0));
  }
}
