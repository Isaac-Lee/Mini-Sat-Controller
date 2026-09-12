package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import msc.domain.flightdynamics.AccessPrediction.*;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class OrekitAccessPredictorIT {
  private OrekitReferenceFrames references() throws Exception {
    return new OrekitReferenceFrames(
        Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")), System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  private InitialState initial(OrekitReferenceFrames frames) {
    return new InitialState(
        "sim-access-v1",
        "sim-1",
        frames.fromUtc("2026-09-01T12:00:00"),
        new Vector(7_000_000, 0, 0),
        new Vector(0, Math.sqrt(3.986004418e14 / 7_000_000), 0),
        "Synthetic circular geometry fixture");
  }

  @Test
  void imagingIsStrictSubsetOfGroundVisibilityAndWindowsClipAtHorizon() throws Exception {
    var frames = references();
    var initial = initial(frames);
    var point =
        frames.groundPoint(
            new Sample(
                initial.epoch(), initial.positionMeters(), initial.velocityMetersPerSecond()));
    var target =
        new Target("under-satellite", point.latitudeDegrees(), point.longitudeDegrees(), 0);
    var horizon =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(1800_000_000_000L)));
    var engine = new OrekitAccessPredictor(frames);
    var contact =
        engine.predict(initial, new Query(Kind.GROUND_CONTACT, target, horizon, 10, 30, 10));
    var imaging =
        engine.predict(initial, new Query(Kind.POINT_IMAGING, target, horizon, 10, 15, 10));
    assertEquals(1, contact.windows().size());
    assertEquals(1, imaging.windows().size());
    assertEquals(initial.epoch(), contact.windows().getFirst().start());
    assertTrue(contact.windows().getFirst().contains(imaging.windows().getFirst()));
    assertTrue(
        imaging.windows().getFirst().end().compareTo(contact.windows().getFirst().end()) < 0);
    assertTrue(imaging.windows().getFirst().end().seconds() - initial.epoch().seconds() > 10);
    assertTrue(contact.windows().getFirst().end().seconds() - initial.epoch().seconds() < 600);
    var shortHorizon =
        new TimeWindow(initial.epoch(), initial.epoch().plus(new MissionDuration(10_000_000_000L)));
    assertEquals(
        java.util.List.of(shortHorizon),
        engine
            .predict(initial, new Query(Kind.GROUND_CONTACT, target, shortHorizon, 10, 30, 10))
            .windows());
    var extended =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(8000_000_000_000L)));
    var repeat =
        engine.predict(initial, new Query(Kind.GROUND_CONTACT, target, extended, 10, 30, 10));
    assertEquals(2, repeat.windows().size());
    assertTrue(repeat.windows().get(1).start().seconds() - initial.epoch().seconds() > 4000);
    assertTrue(repeat.windows().get(1).end().compareTo(extended.end()) < 0);
    assertEquals(frames.digest(), imaging.referenceDigest());
    assertThrows(UnsupportedOperationException.class, () -> imaging.windows().clear());
  }

  @Test
  void oppositeEarthAndInsufficientDurationDoNotProduceOpportunities() throws Exception {
    var frames = references();
    var initial = initial(frames);
    var point =
        frames.groundPoint(
            new Sample(
                initial.epoch(), initial.positionMeters(), initial.velocityMetersPerSecond()));
    var horizon =
        new TimeWindow(
            initial.epoch(), initial.epoch().plus(new MissionDuration(300_000_000_000L)));
    var opposite =
        new Target(
            "opposite",
            -point.latitudeDegrees(),
            Math.IEEEremainder(point.longitudeDegrees() + 180, 360),
            0);
    var engine = new OrekitAccessPredictor(frames);
    assertTrue(
        engine
            .predict(initial, new Query(Kind.GROUND_CONTACT, opposite, horizon, 0, 30, 10))
            .windows()
            .isEmpty());
    var below = new Target("below", point.latitudeDegrees(), point.longitudeDegrees(), 0);
    assertTrue(
        engine
            .predict(initial, new Query(Kind.POINT_IMAGING, below, horizon, 0, 15, 300))
            .windows()
            .isEmpty());
  }
}
