package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.flightdynamics.Trajectory;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;

/**
 * Sampled line of sight required to point at a fixed WGS84 target; does not specify spacecraft
 * attitude or establish sensor coverage. Earth-fixed vectors use the pinned ITRF reference;
 * inertial vectors use EME2000. Off-nadir is measured from radial/geocentric nadir. Topocentric
 * azimuth is measured at the target, clockwise from geodetic north toward east.
 *
 * <p>Results apply only at the returned sample instants. They carry the orbit propagation model,
 * source fingerprint and UTC/EOP archive digest for reproducibility. Small off-nadir does not
 * establish visibility: a target beyond Earth can lie along the extended radial nadir ray.
 */
public final class PointingContracts {
  private PointingContracts() {}

  /**
   * One fixed ground target, one sampling horizon, one sampling step, and the elevation-mask
   * threshold used only to populate {@link RequiredTargetPointingSample#targetVisible()} -- a
   * per-sample recorded fact, never a rejection gate. Carries no off-nadir limit.
   */
  public record RequiredTargetPointingQuery(
      AccessPrediction.Target target,
      TimeWindow horizon,
      int stepSeconds,
      double minimumElevationDegrees) {
    public RequiredTargetPointingQuery {
      Objects.requireNonNull(target);
      Objects.requireNonNull(horizon);
      if (stepSeconds < 1 || stepSeconds > 3600)
        throw new IllegalArgumentException("Sampling step must be 1..3600 seconds");
      // Same numeric range as AccessPrediction.Query#minimumElevationDegrees.
      if (!Double.isFinite(minimumElevationDegrees)
          || minimumElevationDegrees < 0
          || minimumElevationDegrees >= 90)
        throw new IllegalArgumentException("Elevation mask must be 0..<90 degrees");
    }
  }

  /**
   * One sampled instant of required target-pointing geometry. {@link #target()} repeats the query's
   * fixed target verbatim on every sample, so each sample is independently self-describing.
   */
  public record RequiredTargetPointingSample(
      MissionInstant instant,
      Trajectory.Vector satellitePositionEarthFixedMeters,
      Trajectory.Vector lineOfSightEarthFixedUnit,
      Trajectory.Vector lineOfSightInertialUnit,
      double requiredOffNadirDegrees,
      double slantRangeMeters,
      Trajectory.GroundPoint subSatellitePoint,
      AccessPrediction.Target target,
      double topocentricElevationDegrees,
      double topocentricAzimuthDegrees,
      boolean targetVisible) {
    private static final double UNIT_NORM_TOLERANCE = 1e-6;

    public RequiredTargetPointingSample {
      Objects.requireNonNull(instant).requireTai();
      Objects.requireNonNull(satellitePositionEarthFixedMeters);
      requireUnit(lineOfSightEarthFixedUnit, "lineOfSightEarthFixedUnit");
      requireUnit(lineOfSightInertialUnit, "lineOfSightInertialUnit");
      if (!Double.isFinite(requiredOffNadirDegrees)
          || requiredOffNadirDegrees < 0
          || requiredOffNadirDegrees > 180)
        throw new IllegalArgumentException("Invalid off-nadir angle");
      if (!Double.isFinite(slantRangeMeters) || slantRangeMeters <= 0)
        throw new IllegalArgumentException("Invalid slant range");
      Objects.requireNonNull(subSatellitePoint);
      Objects.requireNonNull(target);
      if (!Double.isFinite(topocentricElevationDegrees)
          || Math.abs(topocentricElevationDegrees) > 90)
        throw new IllegalArgumentException("Invalid topocentric elevation");
      if (!Double.isFinite(topocentricAzimuthDegrees)
          || topocentricAzimuthDegrees < 0
          || topocentricAzimuthDegrees >= 360)
        throw new IllegalArgumentException("Invalid topocentric azimuth");
    }

    private static void requireUnit(Trajectory.Vector v, String name) {
      Objects.requireNonNull(v, name);
      double norm = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
      if (!Double.isFinite(norm) || Math.abs(norm - 1.0) > UNIT_NORM_TOLERANCE)
        throw new IllegalArgumentException(name + " must be a unit vector, was norm " + norm);
    }
  }

  /**
   * The only scope value this contract can ever produce: no coverage, no attitude, no feasibility.
   */
  public enum RequiredTargetPointingScope {
    SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE
  }

  /**
   * {@code orbitPropagationModel} is {@code msc.orbit.KeplerianOrbitAdapter#MODEL} for a Cartesian
   * input or {@code msc.orbit.GeneralPerturbationsAdapter#MODEL} for a public-GP input. {@code
   * pointingModel} names the pointing-geometry model itself (radial-nadir convention, frame
   * choices) and is identical for every orbit kind.
   *
   * <p>{@code orbitSourceHash} is the exact source-content hash of the orbit this profile was
   * computed from: for a public-GP source, {@code OrbitReferenceContracts.Snapshot#rawSha256()}
   * (the raw external-source content hash already embedded in the {@code gp-<noradId>-<rawSha256>}
   * solution id); for a Cartesian source, a SHA-256 fingerprint of the canonical {@code
   * Trajectory.InitialState} JSON ({@code msc.platform.Json#fingerprint}), since a Cartesian input
   * carries no raw external source document to hash. See {@code
   * docs/backend/required-target-pointing.md} for why these two are not equivalent guarantees.
   */
  public record RequiredTargetPointingResult(
      String solutionId,
      String spacecraftId,
      String orbitPropagationModel,
      String pointingModel,
      String orbitSourceHash,
      String referenceDigest,
      RequiredTargetPointingQuery query,
      List<RequiredTargetPointingSample> samples,
      RequiredTargetPointingScope scope) {
    public RequiredTargetPointingResult {
      text(solutionId);
      text(spacecraftId);
      text(orbitPropagationModel);
      text(pointingModel);
      text(orbitSourceHash);
      text(referenceDigest);
      Objects.requireNonNull(query);
      samples = List.copyOf(samples);
      if (samples.isEmpty())
        throw new IllegalArgumentException("Empty required-target-pointing profile");
      Objects.requireNonNull(scope);
    }
  }
}
