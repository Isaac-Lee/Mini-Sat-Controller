package msc.orbit;

import java.util.ArrayList;
import java.util.List;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.time.*;
import org.orekit.bodies.AnalyticalSolarPositionProvider;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;
import org.orekit.models.AtmosphericRefractionModel;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.PositionAngleType;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.propagation.events.EventDetector;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.GroundAtNightDetector;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;

/**
 * Sun-elevation ground illumination and finite-Sun penumbra-inclusive spacecraft eclipse,
 * computed only against the pinned UTC/EOP-only reference archive already loaded by {@link
 * OrekitReferenceFrames}, using Orekit 13.1.8's data-free {@link AnalyticalSolarPositionProvider}.
 *
 * <p><b>Accuracy scope, stated explicitly, not implied:</b> {@link #SOLAR_MODEL} is a low-order
 * analytical solar ephemeris, materially less accurate than a JPL/INPOP binary ephemeris. No
 * numeric accuracy bound for it is sourced in this build environment (no Orekit javadoc/source
 * jar and no network access were available to confirm one). {@link #SOLAR_MODEL_ACCURACY_NOTE}
 * (target illumination) and {@link #ECLIPSE_MODEL_ACCURACY_NOTE} (spacecraft eclipse) must be
 * carried in every stored result and must never be read as a mission-qualified bound. See {@code
 * docs/backend/illumination.md}.
 *
 * <p>This class never modifies Orekit's global {@code DataContext} and adds no reference data
 * beyond what {@link OrekitReferenceFrames} already pins.
 */
public final class OrekitIlluminationPredictor {
  public static final String SOLAR_MODEL = "orekit-13.1.8-analytical-solar-position";

  private static final String SOLAR_EPHEMERIS_ACCURACY_NOTE =
      "Low-order analytical solar ephemeris (org.orekit.bodies.AnalyticalSolarPositionProvider),"
          + " materially less accurate than a JPL/INPOP binary ephemeris. No sourced numeric"
          + " accuracy bound is available in this build environment (no Orekit javadoc/source jar"
          + " and no network access were available to confirm one): treat solar geometry derived"
          + " from it as an UNCHARACTERISED, assumed simulation margin only, never as a"
          + " mission-qualified accuracy bound.";

  /** Accuracy note for target (ground-point) illumination results. */
  public static final String SOLAR_MODEL_ACCURACY_NOTE =
      SOLAR_EPHEMERIS_ACCURACY_NOTE
          + " No atmospheric refraction is modelled: minimumSunElevationDegrees is compared"
          + " against the true (geometric) sun elevation, not the apparent (refracted) elevation"
          + " a ground observer would see near the horizon, so reported dawn/dusk crossings do"
          + " not include the refraction correction a real horizon observation would show.";

  public static final String ECLIPSE_MODEL =
      "orekit-13.1.8-finite-sun-penumbra-inclusive-eclipse-WGS84";

  /** Accuracy note for spacecraft eclipse/sunlit results. */
  public static final String ECLIPSE_MODEL_ACCURACY_NOTE =
      SOLAR_EPHEMERIS_ACCURACY_NOTE
          + " Eclipse/sunlit geometry uses a finite-Sun, penumbra-inclusive spherical-shadow test"
          + " against the WGS84 Earth ellipsoid (org.orekit.propagation.events.EclipseDetector"
          + " with withPenumbra()); atmospheric refraction/extinction near the Earth's limb is"
          + " not modelled.";

  /**
   * No atmospheric refraction is modelled: the sun-elevation threshold is compared against the
   * true (unrefracted) geometric elevation. Refraction would only ever make an already-lit point
   * appear lit slightly earlier/later; omitting it is the more conservative (later dawn, earlier
   * dusk in the reported window) choice, and it adds no reference data.
   */
  private static final AtmosphericRefractionModel NO_REFRACTION = trueElevation -> 0.0;

  private static final double ILLUMINATION_MAX_CHECK_SECONDS = 60.0;
  private static final double ECLIPSE_MAX_CHECK_SECONDS = 10.0;
  private static final double ROOT_TOLERANCE_SECONDS = 0.001;
  private static final int MAX_ITERATIONS = 100;

  /**
   * Radius (not altitude) of a bound, non-physical circular orbit used ONLY to drive Orekit's
   * time-stepping event search for ground-point illumination. Arbitrary; carries no spacecraft
   * meaning. See {@link #clockPropagator}.
   */
  private static final double CLOCK_ONLY_ORBIT_RADIUS_METERS =
      Constants.WGS84_EARTH_EQUATORIAL_RADIUS + 700_000;

  private final OrekitReferenceFrames references;
  private final KeplerianOrbitAdapter time = new KeplerianOrbitAdapter();

  public OrekitIlluminationPredictor(OrekitReferenceFrames references) {
    this.references = references;
  }

  public record ToleranceSettings(double rootToleranceSeconds, double maximumCheckSeconds) {}

  public record PointIllumination(
      List<TimeWindow> illuminatedWindows, ToleranceSettings tolerances) {}

  public record SpacecraftEclipse(
      List<TimeWindow> sunlitWindows,
      List<TimeWindow> eclipseWindows,
      ToleranceSettings tolerances) {}

  private void checkHorizon(AbsoluteDate start, AbsoluteDate end) {
    double duration = end.durationFrom(start);
    if (duration > 86400)
      throw new IllegalArgumentException("Illumination search is limited to 24 hours per request");
    references.requireCoverage(start);
    references.requireCoverage(end);
  }

  /**
   * Sun-elevation illumination windows at a single ground point. Independent of any spacecraft
   * trajectory: a synthetic, non-physical bound orbit ({@link #clockPropagator}) is used purely
   * to drive Orekit's time-stepping event search. That substitution is safe only because {@link
   * GroundAtNightDetector#dependsOnTimeOnly()} reports true for this configuration; that is
   * asserted below, not just assumed, so an Orekit upgrade that changed it would fail loudly here
   * instead of silently returning wrong windows.
   */
  public PointIllumination targetIllumination(
      TimeWindow horizon,
      double latitudeDegrees,
      double longitudeDegrees,
      double altitudeMeters,
      double minimumSunElevationDegrees) {
    var start = time.date(horizon.start());
    var end = time.date(horizon.end());
    checkHorizon(start, end);
    var earth = references.earth();
    var geodetic =
        new GeodeticPoint(
            Math.toRadians(latitudeDegrees), Math.toRadians(longitudeDegrees), altitudeMeters);
    var station = new TopocentricFrame(earth, geodetic, "target");
    var sun = new AnalyticalSolarPositionProvider(references.context());
    var detector =
        new GroundAtNightDetector(
                station, sun, Math.toRadians(minimumSunElevationDegrees), NO_REFRACTION)
            .withMaxCheck(ILLUMINATION_MAX_CHECK_SECONDS)
            .withThreshold(ROOT_TOLERANCE_SECONDS)
            .withMaxIter(MAX_ITERATIONS)
            .withHandler(new ContinueOnEvent());
    if (!detector.dependsOnTimeOnly())
      throw new IllegalStateException(
          "GroundAtNightDetector.dependsOnTimeOnly() is false for this Orekit version; the"
              + " synthetic clock-only orbit used to drive time stepping is no longer a safe"
              + " substitute for a real spacecraft trajectory");
    var clock = clockPropagator(start);
    // Sign convention: GroundAtNightDetector.g() is NEGATIVE while the sun is above the
    // configured dawn/dusk elevation (illuminated) and POSITIVE below it (night). This is NOT
    // sourced from Orekit's own javadoc (unavailable in this build environment); it is pinned by
    // two executed regression tests in OrekitIlluminationPredictorIT --
    // targetIlluminationWindowsAreOrderedNonOverlappingAndWithinHorizon (asserts local solar noon
    // at the equator/prime-meridian falls inside a reported illuminated window) and
    // targetIlluminationExcludesPointsBelowConfiguredElevation (asserts a demanding 89-degree
    // threshold excludes that same noon) -- run directly via the JUnit Platform Launcher against
    // the pinned archive, outside Maven; see docs/backend/illumination.md and the handoff for the
    // exact commands and observed output. If this convention were inverted, both tests would fail.
    var windows = negativeGWindows(detector, clock, start, end, horizon);
    return new PointIllumination(
        windows, new ToleranceSettings(ROOT_TOLERANCE_SECONDS, ILLUMINATION_MAX_CHECK_SECONDS));
  }

  /**
   * Finite-Sun, penumbra-inclusive eclipse and sunlit windows for a propagated spacecraft. Sunlit
   * windows are computed as the exact complement of eclipse windows within the horizon, so the
   * two are complementary by construction.
   *
   * <p>Thin delegation to {@link #spacecraftEclipse(MissionInstant, Propagator, TimeWindow)} so
   * the Cartesian two-body path is byte-unchanged; see that overload for the shared logic used by
   * every propagator kind (including the real SGP4/SDP4 GP path in {@code
   * GeneralPerturbationsAdapter}).
   */
  public SpacecraftEclipse spacecraftEclipse(InitialState initial, TimeWindow horizon) {
    return spacecraftEclipse(initial.epoch(), time.propagator(initial), horizon);
  }

  /**
   * Propagator-agnostic eclipse/sunlit computation shared by every orbit kind: the ±7-day
   * validity check is against the caller-supplied {@code epoch} (the Cartesian initial-state
   * epoch, or a GP mean-elements epoch -- never a converted two-body state), and event detection
   * runs directly against the caller-supplied {@code propagator} (a real {@code TLEPropagator}
   * for GP, mirroring how {@link OrekitAccessPredictor} already separates its public typed
   * wrapper from an internal propagator-taking overload). Package-private: {@code
   * GeneralPerturbationsAdapter} is in the same package and calls this directly with its own
   * SGP4/SDP4 propagator; it must never convert GP mean elements into a two-body {@link
   * InitialState} to reach the public overload above.
   *
   * <p>epoch/horizon combination the caller supplies is the epoch used ONLY for the ±7-day
   * validity check above -- never for propagation itself, which is entirely delegated to {@code
   * propagator}.
   */
  SpacecraftEclipse spacecraftEclipse(
      MissionInstant epoch, Propagator propagator, TimeWindow horizon) {
    var start = time.date(horizon.start());
    var end = time.date(horizon.end());
    checkHorizon(start, end);
    if (Math.abs(start.durationFrom(time.date(epoch))) > 7 * 86400
        || Math.abs(end.durationFrom(time.date(epoch))) > 7 * 86400)
      throw new IllegalArgumentException("Initial orbit is outside model validity interval");
    var earth = references.earth();
    var sun = new AnalyticalSolarPositionProvider(references.context());
    var detector =
        new EclipseDetector(sun, Constants.SUN_RADIUS, earth)
            .withPenumbra()
            .withMaxCheck(ECLIPSE_MAX_CHECK_SECONDS)
            .withThreshold(ROOT_TOLERANCE_SECONDS)
            .withMaxIter(MAX_ITERATIONS)
            .withHandler(new ContinueOnEvent());
    // Sign convention: EclipseDetector.g() is POSITIVE while sunlit (out of eclipse, including
    // penumbra-only exposure once withPenumbra() is set) and NEGATIVE while eclipsed. This is NOT
    // sourced from Orekit's own javadoc (unavailable in this build environment); it is pinned by
    // an executed regression test in OrekitIlluminationPredictorIT --
    // spacecraftEclipseClassifiesKnownSunlitAndShadowedStartingGeometryCorrectly -- which places a
    // synthetic circular orbit exactly on the Sun-ward side of Earth (asserted sunlit, zero
    // eclipse) and exactly on the anti-Sun side (asserted eclipsed, zero sunlit) at the same
    // epoch, using the real Sun direction from AnalyticalSolarPositionProvider. Run directly via
    // the JUnit Platform Launcher against the pinned archive, outside Maven; see
    // docs/backend/illumination.md and the handoff for the exact commands and observed output.
    // Eclipse windows are the negative-g intervals; penumbra inclusion widens eclipse relative to
    // a strict-umbra model, which is the conservative direction for a power-supply consumer that
    // must not assume full generation during partial shadow.
    var eclipseWindows = negativeGWindows(detector, propagator, start, end, horizon);
    var sunlitWindows = complement(horizon, eclipseWindows);
    return new SpacecraftEclipse(
        sunlitWindows,
        eclipseWindows,
        new ToleranceSettings(ROOT_TOLERANCE_SECONDS, ECLIPSE_MAX_CHECK_SECONDS));
  }

  /**
   * A bound, non-physical, ARBITRARY circular orbit used only to drive Orekit's
   * propagator/event-detection time stepping for ground-point illumination, which does not
   * itself depend on spacecraft position. Safe only because {@code
   * GroundAtNightDetector.dependsOnTimeOnly()} is asserted true by the caller before this is
   * used; see {@link #targetIllumination}.
   */
  private Propagator clockPropagator(AbsoluteDate epoch) {
    var orbit =
        new KeplerianOrbit(
            CLOCK_ONLY_ORBIT_RADIUS_METERS,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            PositionAngleType.TRUE,
            references.context().getFrames().getEME2000(),
            epoch,
            Constants.WGS84_EARTH_MU);
    return new KeplerianPropagator(orbit);
  }

  /**
   * Windows where {@code detector.g(state) < 0} over {@code [horizon.start, horizon.end)},
   * ordered and non-overlapping, clipped to the horizon. Mirrors {@link
   * OrekitAccessPredictor#predict}'s explicit root-tolerance/max-check event-logging pattern.
   */
  private List<TimeWindow> negativeGWindows(
      EventDetector detector,
      Propagator propagator,
      AbsoluteDate start,
      AbsoluteDate end,
      TimeWindow horizon) {
    var atStart = propagator.propagate(start);
    var windows = new ArrayList<TimeWindow>();
    MissionInstant opening = detector.g(atStart) < 0 ? horizon.start() : null;
    var logger = new EventsLogger();
    propagator.addEventDetector(logger.monitorDetector(detector));
    propagator.propagate(start, end);
    for (var event : logger.getLoggedEvents()) {
      var instant = time.instant(event.getState().getDate());
      if (instant.compareTo(horizon.start()) < 0 || instant.compareTo(horizon.end()) > 0) continue;
      if (!event.isIncreasing()) {
        // g crosses from positive to negative: entering the state of interest.
        if (opening == null) opening = instant;
      } else if (opening != null) {
        // g crosses from negative to positive: exiting the state of interest.
        append(windows, opening, instant);
        opening = null;
      }
    }
    if (opening != null) append(windows, opening, horizon.end());
    return windows;
  }

  private void append(ArrayList<TimeWindow> windows, MissionInstant start, MissionInstant end) {
    if (start.compareTo(end) < 0) windows.add(new TimeWindow(start, end));
  }

  /** The exact complement of {@code windows} (assumed ordered, non-overlapping, horizon-clipped) within {@code horizon}. */
  private List<TimeWindow> complement(TimeWindow horizon, List<TimeWindow> windows) {
    var result = new ArrayList<TimeWindow>();
    var cursor = horizon.start();
    for (var w : windows) {
      if (w.start().compareTo(cursor) > 0) result.add(new TimeWindow(cursor, w.start()));
      if (w.end().compareTo(cursor) > 0) cursor = w.end();
    }
    if (cursor.compareTo(horizon.end()) < 0) result.add(new TimeWindow(cursor, horizon.end()));
    return result;
  }
}
