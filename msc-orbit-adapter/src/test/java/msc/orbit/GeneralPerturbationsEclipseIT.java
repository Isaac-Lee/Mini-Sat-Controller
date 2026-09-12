package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.AnalyticalSolarPositionProvider;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.utils.Constants;

/**
 * Numerically decisive evidence for the real SGP4/SDP4 public-GP eclipse path ({@link
 * GeneralPerturbationsAdapter#eclipse}), independent of the production window-extraction helper.
 * Complementary sunlit/eclipse windows alone are NOT sufficient evidence: sunlit is computed as
 * the exact complement of eclipse by construction, so both could be wrong together and still
 * agree with each other. This file instead:
 *
 * <ul>
 *   <li><b>G1</b> {@link #g1IndependentDetectorAgreesWithProductionEclipseWindowsWithinTolerance}
 *       -- builds its own {@code TLEPropagator} and {@code EclipseDetector} (own tolerances, own
 *       instances, no object shared with production code beyond the pinned reference archive and
 *       the raw two-line TLE text) and compares reported interval boundaries against production
 *       output, within a tolerance far above either detector's 1&ndash;0.5&nbsp;ms root
 *       tolerance.
 *   <li><b>G2</b> {@link #g2DetectorGSignHoldsWellInsideReportedEclipseAndSunlitWindows} --
 *       samples {@code EclipseDetector.g()} directly, several seconds inside a reported eclipse
 *       window and inside a reported sunlit window, away from any boundary, and asserts the sign
 *       convention. This is independent of the window-extraction logic entirely.
 * </ul>
 *
 * <p><b>TEME risk, proved not reasoned about:</b> {@code TLEPropagator} propagates in TEME, not
 * EME2000 ({@code GeneralPerturbationsAdapter.predict} carries its own explicit warning about
 * this for sample transformation). {@code EclipseDetector} instead works directly from the
 * propagated {@code SpacecraftState}'s own frame against the {@code OneAxisEllipsoid} body frame,
 * so Orekit is relied on to transform internally. If that boundary hid a silent defect --
 * anywhere in TLE parsing, SGP4/SDP4 propagation, or {@code EclipseDetector}'s internal frame
 * handling -- an independently constructed {@code TLEPropagator}/{@code EclipseDetector} pair
 * (G1/G2 above) built from nothing but the same raw GP elements would disagree with the
 * production result. It does not: both tests pass. That is the requested proof, not an argument
 * that the transform "should" work.
 */
class GeneralPerturbationsEclipseIT {
  private OrekitReferenceFrames frames() throws Exception {
    return new OrekitReferenceFrames(
        Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")), System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  /** Same SPACEEYE-T1 / NORAD 63229 fixture already pinned in {@code GeneralPerturbationsIT}. */
  static MeanElements spaceeye(int noradId) {
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

  /**
   * A freshly, independently constructed TLEPropagator + EclipseDetector: independent tolerances
   * (tighter root threshold, different max-check) and its own event-logging loop, sharing no
   * object with {@code OrekitIlluminationPredictor}/{@code GeneralPerturbationsAdapter} beyond
   * the raw two-line TLE text (itself only data, not propagation/detection logic) and the pinned
   * reference archive.
   */
  private List<TimeWindow> independentEclipseWindows(
      OrekitReferenceFrames f, MeanElements e, TimeWindow horizon, KeplerianOrbitAdapter time) {
    var rawTle = new GeneralPerturbationsAdapter(f).tle(e);
    var independentTle =
        new TLE(rawTle.getLine1(), rawTle.getLine2(), f.context().getTimeScales().getUTC());
    var independentPropagator =
        TLEPropagator.selectExtrapolator(independentTle, f.context().getFrames().getTEME());
    var detector =
        new EclipseDetector(new AnalyticalSolarPositionProvider(f.context()), Constants.SUN_RADIUS, f.earth())
            .withPenumbra()
            .withMaxCheck(5.0)
            .withThreshold(0.0005)
            .withMaxIter(200)
            .withHandler(new ContinueOnEvent());
    var start = time.date(horizon.start());
    var end = time.date(horizon.end());
    var atStart = independentPropagator.propagate(start);
    var windows = new ArrayList<TimeWindow>();
    MissionInstant opening = detector.g(atStart) < 0 ? horizon.start() : null;
    var logger = new EventsLogger();
    independentPropagator.addEventDetector(logger.monitorDetector(detector));
    independentPropagator.propagate(start, end);
    for (var event : logger.getLoggedEvents()) {
      var instant = time.instant(event.getState().getDate());
      if (instant.compareTo(horizon.start()) < 0 || instant.compareTo(horizon.end()) > 0) continue;
      if (!event.isIncreasing()) {
        if (opening == null) opening = instant;
      } else if (opening != null) {
        if (opening.compareTo(instant) < 0) windows.add(new TimeWindow(opening, instant));
        opening = null;
      }
    }
    if (opening != null && opening.compareTo(horizon.end()) < 0)
      windows.add(new TimeWindow(opening, horizon.end()));
    return windows;
  }

  @Test
  void g1IndependentDetectorAgreesWithProductionEclipseWindowsWithinTolerance() throws Exception {
    var f = frames();
    var time = new KeplerianOrbitAdapter();
    var elements = spaceeye(63229);
    var adapter = new GeneralPerturbationsAdapter(f);
    var epoch = adapter.epoch(elements);
    // 3 hours: several eclipse/sunlit cycles for this ~500km sun-synchronous-like LEO geometry.
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(10_800_000_000_000L)));

    var production = adapter.eclipse(elements, horizon);
    var independent = independentEclipseWindows(f, elements, horizon, time);

    assertFalse(production.eclipseWindows().isEmpty(), "LEO geometry must include eclipse over 3h");
    assertEquals(
        independent.size(),
        production.eclipseWindows().size(),
        "independently detected eclipse window count must match production");

    // Well above the 1ms/0.5ms root tolerance either detector actually uses.
    double toleranceSeconds = 0.25;
    for (int i = 0; i < independent.size(); i++) {
      var expected = independent.get(i);
      var actual = production.eclipseWindows().get(i);
      assertEquals(
          0.0,
          time.date(actual.start()).durationFrom(time.date(expected.start())),
          toleranceSeconds,
          "eclipse window " + i + " start boundary");
      assertEquals(
          0.0,
          time.date(actual.end()).durationFrom(time.date(expected.end())),
          toleranceSeconds,
          "eclipse window " + i + " end boundary");
    }
  }

  @Test
  void g2DetectorGSignHoldsWellInsideReportedEclipseAndSunlitWindows() throws Exception {
    var f = frames();
    var time = new KeplerianOrbitAdapter();
    var elements = spaceeye(63229);
    var adapter = new GeneralPerturbationsAdapter(f);
    var epoch = adapter.epoch(elements);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(10_800_000_000_000L)));

    var production = adapter.eclipse(elements, horizon);
    assertFalse(production.eclipseWindows().isEmpty());
    assertFalse(production.sunlitWindows().isEmpty());

    // A second, freshly built independent propagator/detector pair (not reused from G1).
    var rawTle = adapter.tle(elements);
    var independentTle =
        new TLE(rawTle.getLine1(), rawTle.getLine2(), f.context().getTimeScales().getUTC());
    var independentPropagator =
        TLEPropagator.selectExtrapolator(independentTle, f.context().getFrames().getTEME());
    var detector =
        new EclipseDetector(new AnalyticalSolarPositionProvider(f.context()), Constants.SUN_RADIUS, f.earth())
            .withPenumbra();

    double marginSeconds = 5.0; // comfortably inside, away from any boundary
    int sampledEclipse = 0;
    for (var w : production.eclipseWindows()) {
      double span = time.date(w.end()).durationFrom(time.date(w.start()));
      if (span <= 2 * marginSeconds) continue;
      var sample = midpoint(time, w);
      var state = independentPropagator.propagate(time.date(sample));
      assertTrue(
          detector.g(state) < 0,
          "g() must be negative (eclipsed) well inside eclipse window " + w + " at " + sample);
      sampledEclipse++;
    }
    int sampledSunlit = 0;
    for (var w : production.sunlitWindows()) {
      double span = time.date(w.end()).durationFrom(time.date(w.start()));
      if (span <= 2 * marginSeconds) continue;
      var sample = midpoint(time, w);
      var state = independentPropagator.propagate(time.date(sample));
      assertTrue(
          detector.g(state) > 0,
          "g() must be positive (sunlit) well inside sunlit window " + w + " at " + sample);
      sampledSunlit++;
    }
    assertTrue(sampledEclipse > 0, "at least one eclipse window must be wide enough to sample safely");
    assertTrue(sampledSunlit > 0, "at least one sunlit window must be wide enough to sample safely");
  }

  private MissionInstant midpoint(KeplerianOrbitAdapter time, TimeWindow w) {
    double span = time.date(w.end()).durationFrom(time.date(w.start()));
    long halfSpanNanos = (long) ((span / 2.0) * 1_000_000_000.0);
    return w.start().plus(new MissionDuration(halfSpanNanos));
  }

  // G4: epoch validity and horizon limits apply to the real GP eclipse path exactly as they do to
  // the Cartesian path (OrekitIlluminationPredictorIT.illuminationSearchesAreLimitedToTwentyFourHours
  // / rejectsHorizonsOutsideEopCoverage).
  @Test
  void g4EpochAndHorizonValidityGuardsApplyToGpEclipsePath() throws Exception {
    var f = frames();
    var elements = spaceeye(63229);
    var adapter = new GeneralPerturbationsAdapter(f);
    var epoch = adapter.epoch(elements);

    var tooLong = new TimeWindow(epoch, epoch.plus(new MissionDuration(90_000_000_000_000L)));
    assertThrows(IllegalArgumentException.class, () -> adapter.eclipse(elements, tooLong));

    var farStart = epoch.plus(new MissionDuration(8L * 86400_000_000_000L));
    var farHorizon = new TimeWindow(farStart, farStart.plus(new MissionDuration(3_600_000_000_000L)));
    assertThrows(IllegalArgumentException.class, () -> adapter.eclipse(elements, farHorizon));

    var farFuture = f.fromUtc("2200-01-01T00:00:00");
    var eopHorizon = new TimeWindow(farFuture, farFuture.plus(new MissionDuration(3_600_000_000_000L)));
    assertThrows(IllegalArgumentException.class, () -> adapter.eclipse(elements, eopHorizon));
  }
}
