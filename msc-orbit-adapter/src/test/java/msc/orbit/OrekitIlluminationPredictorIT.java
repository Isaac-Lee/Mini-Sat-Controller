package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.AnalyticalSolarPositionProvider;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.events.GroundAtNightDetector;

/**
 * Live tests against the pinned Orekit reference archive. Mirrors {@link
 * OrekitAccessPredictorIT}'s environment-variable pattern: {@code MSC_TEST_OREKIT_ARCHIVE} and
 * {@code MSC_TEST_OREKIT_SHA256} must point at the same pinned {@code time-frames.zip} used
 * elsewhere in this module.
 */
class OrekitIlluminationPredictorIT {
  private OrekitReferenceFrames references() throws Exception {
    return new OrekitReferenceFrames(
        Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")), System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  // A1: the pinned archive is UTC/EOP-only and carries no JPL/INPOP solar ephemeris. This test
  // pins that scope assumption so a future addition of a DE/ephemeris file to the archive is
  // caught, not silently trusted.
  //
  // Asserted directly over the real ZIP entries (java.util.zip.ZipFile, JDK-only) rather than by
  // parsing the companion manifest.json: a hand-maintained metadata file can drift from the
  // archive's actual contents, but the ZIP's own entry list cannot. msc-orbit-adapter is
  // deliberately a thin numerical adapter carrying only msc-ports and Orekit, and this assertion
  // does not justify adding a serialization dependency to the module's production/test
  // classpath -- java.util.zip is JDK-only.
  @Test
  void pinnedArchiveIsUtcEopOnlyWithNoSolarEphemeris() throws Exception {
    var archive = Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE"));
    List<String> paths = new ArrayList<>();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) paths.add(entries.nextElement().getName());
    }

    // Exactly the two pinned UTC/EOP files this archive is documented (manifest.json) to carry:
    // tai-utc.dat and Earth-Orientation-Parameters/IAU-2000/finals2000A.all. Asserted as an exact
    // set (not just "contains" or "size == 2") so a renamed, moved, or additional entry fails
    // loudly rather than passing a weaker containment check.
    assertEquals(
        java.util.Set.of(
            "tai-utc.dat", "Earth-Orientation-Parameters/IAU-2000/finals2000A.all"),
        java.util.Set.copyOf(paths),
        "Archive must contain exactly the two pinned UTC/EOP files, nothing else");

    for (String path : paths) {
      assertFalse(
          path.matches("(?i).*\\.(bsp|dat\\.de\\d+)$")
              || path.toLowerCase().matches(".*(de[0-9]{3}|inpop|jpl.?ephem).*"),
          "Archive file '"
              + path
              + "' looks like a JPL/INPOP ephemeris; AnalyticalSolarPositionProvider was chosen"
              + " specifically because none is present, and CelestialBodyFactory.getSun() would"
              + " fail without one. Re-evaluate the solar model choice before proceeding.");
    }
  }

  private static final double MU = 3.986004418e14;

  // A4: target illumination windows honour the configured minimum sun elevation and are
  // contained in, ordered within, and non-overlapping over the requested horizon.
  @Test
  void targetIlluminationWindowsAreOrderedNonOverlappingAndWithinHorizon() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var noon = frames.fromUtc("2026-09-01T12:00:00");
    var horizon = new TimeWindow(noon, noon.plus(new MissionDuration(86_000_000_000_000L)));
    var result = predictor.targetIllumination(horizon, 0, 0, 0, 0);
    assertFalse(result.illuminatedWindows().isEmpty(), "Equator/prime-meridian noon must be lit");
    for (var window : result.illuminatedWindows())
      assertTrue(horizon.contains(window), "Window " + window + " must be within the horizon");
    for (int i = 1; i < result.illuminatedWindows().size(); i++)
      assertTrue(
          result.illuminatedWindows().get(i - 1).end().compareTo(result.illuminatedWindows().get(i).start()) < 0,
          "Windows must be ordered and non-overlapping");
    // The request epoch (local solar noon at longitude 0) must fall inside an illuminated window.
    assertTrue(
        result.illuminatedWindows().stream()
            .anyMatch(w -> w.start().compareTo(noon) <= 0 && w.end().compareTo(noon) > 0),
        "Local solar noon must be inside an illuminated window");
  }

  // A4 (negative case): a threshold above the noon elevation at this point/date must exclude it.
  @Test
  void targetIlluminationExcludesPointsBelowConfiguredElevation() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var noon = frames.fromUtc("2026-09-01T12:00:00");
    var horizon = new TimeWindow(noon, noon.plus(new MissionDuration(3600_000_000_000L)));
    // Sun elevation at equator/prime-meridian around this noon is well under 89 degrees.
    var result = predictor.targetIllumination(horizon, 0, 0, 0, 89);
    assertTrue(result.illuminatedWindows().isEmpty());
  }

  private InitialState circularEquatorialOrbit(MissionInstant epoch) {
    return new InitialState(
        "sim-illum-v1",
        "sim-1",
        epoch,
        new Vector(7_000_000, 0, 0),
        new Vector(0, Math.sqrt(MU / 7_000_000), 0),
        "Synthetic circular geometry fixture");
  }

  // A5: spacecraft eclipse and sunlit windows are complementary over the horizon (by
  // construction) and both are actually produced for a known LEO geometry.
  @Test
  void spacecraftEclipseAndSunlitWindowsAreComplementaryAndBothOccur() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit(epoch);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(10_800_000_000_000L)));
    var result = predictor.spacecraftEclipse(initial, horizon);

    assertFalse(result.eclipseWindows().isEmpty(), "Known LEO geometry must include eclipse");
    assertFalse(result.sunlitWindows().isEmpty(), "Known LEO geometry must include sunlit time");

    var all = new java.util.ArrayList<TimeWindow>();
    all.addAll(result.eclipseWindows());
    all.addAll(result.sunlitWindows());
    all.sort((a, b) -> a.start().compareTo(b.start()));
    assertEquals(horizon.start(), all.get(0).start());
    assertEquals(horizon.end(), all.get(all.size() - 1).end());
    for (int i = 1; i < all.size(); i++)
      assertEquals(
          0,
          all.get(i - 1).end().compareTo(all.get(i).start()),
          "Eclipse and sunlit windows must exactly tile the horizon with no gap or overlap");

    for (var l : List.of(result.eclipseWindows(), result.sunlitWindows()))
      for (int i = 1; i < l.size(); i++)
        assertTrue(l.get(i - 1).end().compareTo(l.get(i).start()) <= 0);
  }

  // E6: the existing 24-hour-per-request search limit applies to both new query types.
  @Test
  void illuminationSearchesAreLimitedToTwentyFourHours() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var start = frames.fromUtc("2026-09-01T00:00:00");
    var tooLong = new TimeWindow(start, start.plus(new MissionDuration(90_000_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> predictor.targetIllumination(tooLong, 0, 0, 0, 0));
    var initial = circularEquatorialOrbit(start);
    assertThrows(
        IllegalArgumentException.class, () -> predictor.spacecraftEclipse(initial, tooLong));
  }

  // E6: requireCoverage is enforced on both horizon ends, mirroring OrekitAccessPredictor.
  @Test
  void rejectsHorizonsOutsideEopCoverage() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var farFuture = frames.fromUtc("2200-01-01T00:00:00");
    var horizon = new TimeWindow(farFuture, farFuture.plus(new MissionDuration(3600_000_000_000L)));
    assertThrows(
        IllegalArgumentException.class, () -> predictor.targetIllumination(horizon, 0, 0, 0, 0));
  }

  // Pins the invariant OrekitIlluminationPredictor.targetIllumination relies on to justify using
  // a synthetic, non-physical clock orbit instead of a real spacecraft trajectory: the detector's
  // g() truly depends only on time, not on the propagated state's position. If a future Orekit
  // version ever made this false, this test (and the runtime check in targetIllumination) would
  // fail loudly instead of silently returning wrong windows.
  @Test
  void groundAtNightDetectorDependsOnTimeOnlyInvariantHolds() throws Exception {
    var frames = references();
    var earth = frames.earth();
    var geodetic = new GeodeticPoint(0, 0, 0);
    var station = new TopocentricFrame(earth, geodetic, "probe");
    var sun = new AnalyticalSolarPositionProvider(frames.context());
    var detector = new GroundAtNightDetector(station, sun, Math.toRadians(-6), trueElevation -> 0.0);
    assertTrue(detector.dependsOnTimeOnly());
  }

  // A5 (semantic, not just structural): proves the eclipse/sunlit sign convention against known
  // Sun-relative geometry, using the real Sun direction from AnalyticalSolarPositionProvider and
  // the actual production code path (predictor.spacecraftEclipse). A synthetic circular orbit
  // placed exactly on the Sun-ward side of Earth must be classified fully sunlit; placed exactly
  // on the anti-Sun side (deep in Earth's shadow), fully eclipsed.
  @Test
  void spacecraftEclipseClassifiesKnownSunlitAndShadowedStartingGeometryCorrectly() throws Exception {
    var frames = references();
    var predictor = new OrekitIlluminationPredictor(frames);
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var time = new KeplerianOrbitAdapter();
    var date = time.date(epoch);
    var eme2000 = frames.context().getFrames().getEME2000();
    var sun = new AnalyticalSolarPositionProvider(frames.context());
    Vector3D sunDirection = sun.getPosition(date, eme2000).normalize();
    double r = 7_000_000;
    Vector3D axis = Math.abs(sunDirection.getZ()) < 0.9 ? Vector3D.PLUS_K : Vector3D.PLUS_I;
    Vector3D perpendicularVelocityDirection = Vector3D.crossProduct(sunDirection, axis).normalize();
    Vector3D velocity = perpendicularVelocityDirection.scalarMultiply(Math.sqrt(MU / r));

    var sunSide = sunDirection.scalarMultiply(r);
    var antiSunSide = sunDirection.scalarMultiply(-r);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));

    var sunlitFixture =
        new InitialState(
            "sim-illum-sunlit",
            "sim-1",
            epoch,
            new Vector(sunSide.getX(), sunSide.getY(), sunSide.getZ()),
            new Vector(velocity.getX(), velocity.getY(), velocity.getZ()),
            "Synthetic Sun-ward geometry fixture");
    var shadowedFixture =
        new InitialState(
            "sim-illum-eclipsed",
            "sim-1",
            epoch,
            new Vector(antiSunSide.getX(), antiSunSide.getY(), antiSunSide.getZ()),
            new Vector(velocity.getX(), velocity.getY(), velocity.getZ()),
            "Synthetic anti-Sun (deep shadow) geometry fixture");

    var sunlitResult = predictor.spacecraftEclipse(sunlitFixture, horizon);
    assertTrue(
        sunlitResult.eclipseWindows().isEmpty(),
        "A spacecraft placed exactly on the Sun-ward side of Earth must not be classified eclipsed");
    assertEquals(1, sunlitResult.sunlitWindows().size());
    assertEquals(horizon, sunlitResult.sunlitWindows().get(0));

    var shadowedResult = predictor.spacecraftEclipse(shadowedFixture, horizon);
    assertTrue(
        shadowedResult.sunlitWindows().isEmpty(),
        "A spacecraft placed exactly on the anti-Sun side of Earth must not be classified sunlit");
    assertEquals(1, shadowedResult.eclipseWindows().size());
    assertEquals(horizon, shadowedResult.eclipseWindows().get(0));
  }
}
