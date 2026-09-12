package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import msc.domain.flightdynamics.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.*;

class GeneralPerturbationsIT {
  OrekitReferenceFrames frames() throws Exception {
    return new OrekitReferenceFrames(
        Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")), System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  MeanElements spaceeye(int id) {
    return new MeanElements(
        id,
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

  @Test
  void gpMatchesTleParsingAndTransformedEarthTrack() throws Exception {
    var f = frames();
    var adapter = new GeneralPerturbationsAdapter(f);
    var elements = spaceeye(63229);
    var tle = adapter.tle(elements);
    var parsed = new TLE(tle.getLine1(), tle.getLine2(), f.context().getTimeScales().getUTC());
    assertEquals(
        elements.meanMotionDot() * 4 * Math.PI / (86400.0 * 86400),
        parsed.getMeanMotionFirstDerivative(),
        1e-20);
    var check = TLEPropagator.selectExtrapolator(parsed, f.context().getFrames().getTEME());
    var start = adapter.epoch(elements);
    var end = start.plus(new MissionDuration(86400_000_000_000L));
    var prediction = adapter.predict("gp", elements, new TimeWindow(start, end), 600);
    assertEquals(144, prediction.samples().size());
    assertEquals("EME2000", prediction.frame());
    var time = new KeplerianOrbitAdapter();
    for (var sample : prediction.samples()) {
      var expected =
          check
              .propagate(time.date(sample.time()))
              .getPVCoordinates(f.context().getFrames().getEME2000());
      // Traditional TLE rounds eccentricity and BSTAR: GP precision is retained internally.
      assertTrue(
          expected.getPosition().distance(KeplerianOrbitAdapter.vector(sample.positionMeters()))
              < 20);
      var ground = f.groundPoint(sample);
      assertTrue(ground.altitudeMeters() > 300000 && ground.altitudeMeters() < 650000);
    }
    var access =
        adapter.access(
            "gp",
            "norad-63229",
            elements,
            new AccessPrediction.Query(
                AccessPrediction.Kind.GROUND_CONTACT,
                new AccessPrediction.Target("daejeon", 36.35, 127.38, 100),
                new TimeWindow(start, end),
                5,
                30,
                10));
    assertFalse(access.windows().isEmpty());
    assertTrue(access.model().contains("SGP4"));
  }

  @Test
  void longNoradIdsRemainDistinctAndOldElementsFailClosed() throws Exception {
    var adapter = new GeneralPerturbationsAdapter(frames());
    var e = spaceeye(123456789);
    assertEquals(123456789, adapter.tle(e).getSatelliteNumber());
    var epoch = adapter.epoch(e);
    var start = epoch.plus(new MissionDuration(8 * 86400_000_000_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.predict(
                "gp",
                e,
                new TimeWindow(start, start.plus(new MissionDuration(1_000_000_000L))),
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.predict(
                "gp",
                e,
                new TimeWindow(epoch, epoch.plus(new MissionDuration(86400_000_000_000L))),
                1));
  }
}
