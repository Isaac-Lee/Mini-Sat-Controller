package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

/**
 * Run explicitly after scripts/fetch-orekit-reference.py; absence is a failure, never a skipped
 * proof.
 */
class OrekitReferenceFramesIT {
  private Path archive() {
    return Path.of(
        java.util.Objects.requireNonNull(
            System.getenv("MSC_TEST_OREKIT_ARCHIVE"), "Reference archive required"));
  }

  private String digest() {
    return java.util.Objects.requireNonNull(
        System.getenv("MSC_TEST_OREKIT_SHA256"), "Reference digest required");
  }

  @Test
  void utcLeapSecondMapsToThreeDistinctPhysicalInstants() throws Exception {
    var frames = new OrekitReferenceFrames(archive(), digest());
    var before = frames.fromUtc("2016-12-31T23:59:59");
    var leap = frames.fromUtc("2016-12-31T23:59:60");
    var after = frames.fromUtc("2017-01-01T00:00:00");
    assertEquals(1, leap.seconds() - before.seconds());
    assertEquals(1, after.seconds() - leap.seconds());
    assertTrue(frames.toUtc(leap).startsWith("2016-12-31T23:59:60"));
    assertEquals(1483228837L, after.seconds());
  }

  @Test
  void rotatesInertialPointToEarthAndRejectsMissingEarthOrientation() throws Exception {
    var frames = new OrekitReferenceFrames(archive(), digest());
    var t = frames.fromUtc("2026-09-01T12:00:00");
    var sample = new Sample(t, new Vector(7_000_000, 0, 0), new Vector(0, 7500, 0));
    var point = frames.groundPoint(sample);
    assertTrue(Math.abs(point.latitudeDegrees()) < 1);
    assertTrue(point.altitudeMeters() > 621_000 && point.altitudeMeters() < 623_000);
    var next =
        frames.groundPoint(
            new Sample(
                t.plus(new MissionDuration(3600_000_000_000L)),
                sample.positionMeters(),
                sample.velocityMetersPerSecond()));
    double delta = Math.IEEEremainder(next.longitudeDegrees() - point.longitudeDegrees(), 360);
    assertEquals(-15.041, delta, 0.02);
    assertEquals(digest(), point.referenceDigest());
    var outside =
        new Sample(
            frames.fromUtc("2200-01-01T00:00:00"),
            sample.positionMeters(),
            sample.velocityMetersPerSecond());
    assertThrows(IllegalArgumentException.class, () -> frames.groundPoint(outside));
  }

  @Test
  void refusesTamperedReferenceIdentity() {
    assertThrows(
        IllegalArgumentException.class, () -> new OrekitReferenceFrames(archive(), "0".repeat(64)));
  }
}
