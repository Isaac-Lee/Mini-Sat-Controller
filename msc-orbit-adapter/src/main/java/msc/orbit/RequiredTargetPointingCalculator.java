package msc.orbit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.flightdynamics.Trajectory;
import msc.domain.flightdynamics.Trajectory.GroundPoint;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.time.MissionDuration;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.Propagator;

/**
 * Samples required satellite-to-target line of sight using the existing Cartesian two-body or GP
 * SGP4/SDP4 propagator. GP mean elements retain their original numeric precision.
 *
 * <p>WGS84 target geometry is resolved in ITRF and EME2000. Off-nadir uses radial/geocentric nadir;
 * elevation and azimuth use the target's geodetic topocentric frame. A profile provides geometric
 * samples, not continuous-interval bounds, actual attitude or sensor coverage.
 */
public final class RequiredTargetPointingCalculator {
  /**
   * Names only the pointing-geometry model (radial-nadir convention, frame choices); identical for
   * every orbit kind. The orbit-propagation model that actually produced the trajectory (Keplerian
   * two-body vs SGP4/SDP4) is a separate concern, bound by the caller -- mirroring {@code
   * IlluminationContracts.SpacecraftEclipseResult}'s split between {@code eclipseModel} and {@code
   * orbitPropagationModel}.
   */
  public static final String POINTING_MODEL =
      "orekit-13.1.8-WGS84-radial-geocentric-nadir-required-target-pointing";

  /**
   * Same bound {@code KeplerianOrbitAdapter#predict}/{@code GeneralPerturbationsAdapter#predict}
   * use.
   */
  public static final long MAX_HORIZON_SECONDS = 7 * 86400;

  /**
   * Same cap {@code KeplerianOrbitAdapter#predict}/{@code GeneralPerturbationsAdapter#predict} use.
   */
  public static final int MAX_SAMPLE_COUNT = 20000;

  private final OrekitReferenceFrames references;
  private final KeplerianOrbitAdapter time = new KeplerianOrbitAdapter();

  public RequiredTargetPointingCalculator(OrekitReferenceFrames references) {
    this.references = references;
  }

  /**
   * One sampled instant. {@link #target()} repeats the caller's fixed target verbatim on every
   * sample (the target does not move); it is still carried per sample so each sample is
   * independently self-describing.
   */
  public record Sample(
      MissionInstant instant,
      Trajectory.Vector satellitePositionEarthFixedMeters,
      Trajectory.Vector lineOfSightEarthFixedUnit,
      Trajectory.Vector lineOfSightInertialUnit,
      double requiredOffNadirDegrees,
      double slantRangeMeters,
      GroundPoint subSatellitePoint,
      AccessPrediction.Target target,
      double topocentricElevationDegrees,
      double topocentricAzimuthDegrees,
      boolean targetVisible) {}

  /** Raw computation output; carries no solution/spacecraft identity -- the caller binds that. */
  public record Profile(String pointingModel, List<Sample> samples) {}

  /** Cartesian path: uses {@code initial}'s own two-body propagator, unchanged. */
  public Profile profile(
      InitialState initial,
      AccessPrediction.Target target,
      TimeWindow horizon,
      int stepSeconds,
      double minimumElevationDegrees) {
    return profile(
        initial.epoch(),
        time.propagator(initial),
        target,
        horizon,
        stepSeconds,
        minimumElevationDegrees);
  }

  /**
   * Public-GP path: uses the real SGP4/SDP4 {@code TLEPropagator} from {@code
   * GeneralPerturbationsAdapter#propagator(MeanElements)} directly (package-private access from
   * within {@code msc.orbit}); never converts {@code elements} into a two-body {@link
   * InitialState}.
   */
  public Profile profile(
      MeanElements elements,
      AccessPrediction.Target target,
      TimeWindow horizon,
      int stepSeconds,
      double minimumElevationDegrees) {
    var gp = new GeneralPerturbationsAdapter(references);
    return profile(
        gp.epoch(elements),
        gp.propagator(elements),
        target,
        horizon,
        stepSeconds,
        minimumElevationDegrees);
  }

  /**
   * Propagator-agnostic core, shared by every orbit kind. {@code epoch} is used ONLY for the
   * &plusmn;7-day model-validity check (mirroring {@code OrekitAccessPredictor#predict} and {@code
   * OrekitIlluminationPredictor#spacecraftEclipse}); actual propagation is entirely delegated to
   * {@code propagator}. Package-private so a same-package IT can exercise it directly against a
   * hand-built propagator, exactly as {@code OrekitIlluminationPredictor}'s equivalent overload is
   * package-private for the same reason.
   */
  Profile profile(
      MissionInstant epoch,
      Propagator propagator,
      AccessPrediction.Target target,
      TimeWindow horizon,
      int stepSeconds,
      double minimumElevationDegrees) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(horizon);
    if (stepSeconds < 1 || stepSeconds > 3600)
      throw new IllegalArgumentException("Sampling step must be 1..3600 seconds");
    if (!Double.isFinite(minimumElevationDegrees)
        || minimumElevationDegrees < 0
        || minimumElevationDegrees >= 90)
      throw new IllegalArgumentException("Elevation mask must be 0..<90 degrees");

    var start = time.date(horizon.start());
    var end = time.date(horizon.end());
    double span = end.durationFrom(start);
    if (span > MAX_HORIZON_SECONDS || Math.ceil(span / stepSeconds) > MAX_SAMPLE_COUNT)
      throw new IllegalArgumentException(
          "Required-target-pointing profile must fit "
              + (MAX_HORIZON_SECONDS / 86400)
              + " days and "
              + MAX_SAMPLE_COUNT
              + " samples at the requested step");
    if (Math.abs(start.durationFrom(time.date(epoch))) > MAX_HORIZON_SECONDS
        || Math.abs(end.durationFrom(time.date(epoch))) > MAX_HORIZON_SECONDS)
      throw new IllegalArgumentException("Initial orbit is outside model validity interval");
    references.requireCoverage(start);
    references.requireCoverage(end);

    var earth = references.earth();
    var earthFixedFrame = earth.getBodyFrame();
    var inertialFrame = references.context().getFrames().getEME2000();
    var geodetic =
        new GeodeticPoint(
            Math.toRadians(target.latitudeDegrees()),
            Math.toRadians(target.longitudeDegrees()),
            target.altitudeMeters());
    var station = new TopocentricFrame(earth, geodetic, target.id());
    // Fixed in the Earth-fixed frame at every instant; the target does not move relative to the
    // Earth. Never reused as-is in the inertial frame below -- it is re-transformed per sample,
    // since the Earth-fixed-to-inertial rotation changes with time.
    var targetPositionEarthFixed = earth.transform(geodetic);

    var samples = new ArrayList<Sample>();
    for (var instant = horizon.start();
        instant.compareTo(horizon.end()) < 0;
        instant = instant.plus(new MissionDuration(stepSeconds * 1_000_000_000L))) {
      var date = time.date(instant);
      var state = propagator.propagate(date);

      var positionEarthFixed = state.getPosition(earthFixedFrame);
      var pvInertial = state.getPVCoordinates(inertialFrame);
      var positionInertial = pvInertial.getPosition();

      var losEarthFixedVector = targetPositionEarthFixed.subtract(positionEarthFixed);
      double slantRangeMeters = losEarthFixedVector.getNorm();
      var losEarthFixedUnit = losEarthFixedVector.normalize();

      // Re-resolve the (fixed, Earth-frame) target position into the inertial frame at this
      // instant's rotation, rather than reusing any Earth-fixed quantity directly as inertial:
      // conflating the two frames is exactly the silent error this profile must not hide.
      var earthToInertial = earthFixedFrame.getTransformTo(inertialFrame, date);
      var targetPositionInertial = earthToInertial.transformPosition(targetPositionEarthFixed);
      var losInertialUnit = targetPositionInertial.subtract(positionInertial).normalize();

      double offNadirDegrees =
          Math.toDegrees(Vector3D.angle(positionEarthFixed.negate(), losEarthFixedVector));

      double elevationDegrees =
          Math.toDegrees(station.getElevation(positionEarthFixed, earthFixedFrame, date));
      double azimuthDegrees =
          normalizeDegrees(
              Math.toDegrees(station.getAzimuth(positionEarthFixed, earthFixedFrame, date)));
      boolean visible = elevationDegrees >= minimumElevationDegrees;

      var subSatellite =
          references.groundPoint(
              new Trajectory.Sample(
                  instant,
                  KeplerianOrbitAdapter.vector(positionInertial),
                  KeplerianOrbitAdapter.vector(pvInertial.getVelocity())));

      samples.add(
          new Sample(
              instant,
              KeplerianOrbitAdapter.vector(positionEarthFixed),
              KeplerianOrbitAdapter.vector(losEarthFixedUnit),
              KeplerianOrbitAdapter.vector(losInertialUnit),
              offNadirDegrees,
              slantRangeMeters,
              subSatellite,
              target,
              elevationDegrees,
              azimuthDegrees,
              visible));
    }
    if (samples.isEmpty())
      throw new IllegalArgumentException("Horizon and step produced zero samples");
    return new Profile(POINTING_MODEL, samples);
  }

  /** Orekit's raw azimuth is not guaranteed pre-normalised to [0, 360); make the range explicit. */
  private static double normalizeDegrees(double degrees) {
    double normalized = degrees % 360.0;
    return normalized < 0 ? normalized + 360.0 : normalized;
  }
}
