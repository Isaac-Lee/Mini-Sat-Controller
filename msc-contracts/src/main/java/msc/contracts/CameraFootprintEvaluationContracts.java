package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import msc.domain.time.MissionInstant;

/**
 * Sampled camera footprints under an explicit SIMULATION camera model. AOI coverage uses the
 * WGS84_LOCAL_LINEAR_RECTANGLE_V1 approximation; it does not establish continuous exposure,
 * geographic conservatism, Tasking ownership or request fulfillment.
 */
public final class CameraFootprintEvaluationContracts {
  private CameraFootprintEvaluationContracts() {}

  /** The only supported AOI-to-tangent-plane mapping law; see the class javadoc. */
  public static final String AOI_MAPPING = "WGS84_LOCAL_LINEAR_RECTANGLE_V1";

  /** Maximum allowed separation, in degrees, between the pointing target and the AOI centre. */
  public static final double AOI_CENTER_TOLERANCE_DEGREES = 1e-9;

  /** Supported domain: absolute target latitude, in degrees. */
  public static final double AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES = 80;

  /** Supported domain: mapped AOI width, in metres. */
  public static final double AOI_MAXIMUM_WIDTH_METERS = 50_000;

  /** Supported domain: mapped AOI height, in metres. */
  public static final double AOI_MAXIMUM_HEIGHT_METERS = 50_000;

  /**
   * Fixed disclaimer every {@link AoiPlanarMapping} must carry verbatim: the mapping's sign
   * relative to the true geographic rectangle is not established, so it is never a conservative
   * geographic coverage bound.
   */
  public static final String AOI_MAPPING_APPROXIMATION_NOTE =
      "Local tangent-plane linear rectangle approximation of the requested geographic AOI, not"
          + " the exact projection or the bounding box of its geographic corners. The sign of"
          + " this approximation relative to the true geographic rectangle is not established:"
          + " this is not a conservative geographic coverage claim. No terrain qualification is"
          + " expressed. Coverage is exact only for this declared planar rectangle.";

  /**
   * The explicit calculation query: one owned pointing result, one exact camera model version, and
   * one AOI supplied as planner/user intent for this computation -- never injected Tasking
   * ownership. See the class javadoc's "AOI is an explicit calculation query" paragraph.
   */
  public record CameraFootprintEvaluationQuery(
      String pointingResultId, long cameraModelVersion, TaskingContracts.Area area) {
    public CameraFootprintEvaluationQuery {
      text(pointingResultId);
      if (cameraModelVersion < 1)
        throw new IllegalArgumentException("Exact positive camera model version required");
      Objects.requireNonNull(area);
    }
  }

  /** One point in the tangent-plane chart, in metres east/north of the pointing target. */
  public record Point(double eastMeters, double northMeters) {
    public Point {
      if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters))
        throw new IllegalArgumentException("Non-finite planar point");
    }
  }

  /**
   * Report of the {@value #AOI_MAPPING} conversion actually applied. See the class javadoc's "AOI
   * mapping law" paragraph; every field here must be present on every result so a consumer can
   * never receive a coverage fraction without also receiving its scope and limits.
   */
  public record AoiPlanarMapping(
      String mapping,
      String environment,
      String approximationNote,
      double centerLatitudeDegrees,
      double centerLongitudeDegrees,
      double widthMeters,
      double heightMeters,
      double maximumAbsoluteLatitudeDegrees,
      double maximumWidthMeters,
      double maximumHeightMeters) {
    public AoiPlanarMapping {
      if (!AOI_MAPPING.equals(mapping))
        throw new IllegalArgumentException("Only the declared AOI mapping law is supported");
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException("Only explicitly simulated AOI mapping is supported");
      if (!AOI_MAPPING_APPROXIMATION_NOTE.equals(approximationNote))
        throw new IllegalArgumentException("Approximation disclaimer must be reported verbatim");
      if (!Double.isFinite(centerLatitudeDegrees)
          || Math.abs(centerLatitudeDegrees) > AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES)
        throw new IllegalArgumentException("Invalid AOI centre latitude");
      if (!Double.isFinite(centerLongitudeDegrees) || Math.abs(centerLongitudeDegrees) > 180)
        throw new IllegalArgumentException("Invalid AOI centre longitude");
      if (!Double.isFinite(widthMeters)
          || widthMeters <= 0
          || widthMeters > AOI_MAXIMUM_WIDTH_METERS)
        throw new IllegalArgumentException("Invalid mapped AOI width");
      if (!Double.isFinite(heightMeters)
          || heightMeters <= 0
          || heightMeters > AOI_MAXIMUM_HEIGHT_METERS)
        throw new IllegalArgumentException("Invalid mapped AOI height");
      if (maximumAbsoluteLatitudeDegrees != AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES
          || maximumWidthMeters != AOI_MAXIMUM_WIDTH_METERS
          || maximumHeightMeters != AOI_MAXIMUM_HEIGHT_METERS)
        throw new IllegalArgumentException("Reported domain limits must equal the declared law");
    }
  }

  /**
   * One sampled instant's footprint evidence. {@code requiredOffNadirDegrees} and {@code
   * topocentricElevationDegrees} repeat the owned pointing sample's own values and their
   * comparisons against the pinned camera model's limits are always present, independent of
   * projection outcome. Footprint-derived facts ({@code footprintCorners}, {@code
   * footprintAreaSquareMeters}, {@code maximumPixelAxisSpacingBoundMeters}, {@code
   * exceedsMaximumGroundSampleDistance}, {@code coverageFraction}) are present if and only if
   * {@code outcome == COMPUTED}; {@code unevaluatedReason} is present if and only if {@code outcome
   * == UNEVALUATED}. No field here is ever converted into an overall verdict.
   */
  public record CameraFootprintSample(
      MissionInstant instant,
      double requiredOffNadirDegrees,
      boolean exceedsMaximumOffNadir,
      double topocentricElevationDegrees,
      boolean belowMinimumTargetElevation,
      SampleOutcome outcome,
      Optional<String> unevaluatedReason,
      Optional<List<Point>> footprintCorners,
      Optional<Double> footprintAreaSquareMeters,
      Optional<Double> maximumPixelAxisSpacingBoundMeters,
      Optional<Boolean> exceedsMaximumGroundSampleDistance,
      Optional<Double> coverageFraction) {
    public enum SampleOutcome {
      COMPUTED,
      UNEVALUATED
    }

    public CameraFootprintSample {
      Objects.requireNonNull(instant).requireTai();
      if (!Double.isFinite(requiredOffNadirDegrees)
          || requiredOffNadirDegrees < 0
          || requiredOffNadirDegrees > 180)
        throw new IllegalArgumentException("Invalid off-nadir angle");
      if (!Double.isFinite(topocentricElevationDegrees)
          || Math.abs(topocentricElevationDegrees) > 90)
        throw new IllegalArgumentException("Invalid topocentric elevation");
      Objects.requireNonNull(outcome);
      unevaluatedReason = unevaluatedReason == null ? Optional.empty() : unevaluatedReason;
      footprintCorners =
          footprintCorners == null ? Optional.empty() : footprintCorners.map(List::copyOf);
      footprintAreaSquareMeters =
          footprintAreaSquareMeters == null ? Optional.empty() : footprintAreaSquareMeters;
      maximumPixelAxisSpacingBoundMeters =
          maximumPixelAxisSpacingBoundMeters == null
              ? Optional.empty()
              : maximumPixelAxisSpacingBoundMeters;
      exceedsMaximumGroundSampleDistance =
          exceedsMaximumGroundSampleDistance == null
              ? Optional.empty()
              : exceedsMaximumGroundSampleDistance;
      coverageFraction = coverageFraction == null ? Optional.empty() : coverageFraction;
      if (outcome == SampleOutcome.COMPUTED) {
        if (unevaluatedReason.isPresent())
          throw new IllegalArgumentException("A computed sample must not carry a failure reason");
        if (footprintCorners.isEmpty()
            || footprintAreaSquareMeters.isEmpty()
            || maximumPixelAxisSpacingBoundMeters.isEmpty()
            || exceedsMaximumGroundSampleDistance.isEmpty()
            || coverageFraction.isEmpty())
          throw new IllegalArgumentException(
              "A computed sample requires footprint, spacing bound, GSD-bound comparison and"
                  + " coverage fraction");
        if (footprintCorners.get().size() < 3)
          throw new IllegalArgumentException("Footprint requires at least 3 corners");
        double area = footprintAreaSquareMeters.get();
        if (!Double.isFinite(area) || area <= 0)
          throw new IllegalArgumentException("Invalid footprint area");
        double spacing = maximumPixelAxisSpacingBoundMeters.get();
        if (!Double.isFinite(spacing) || spacing <= 0)
          throw new IllegalArgumentException("Invalid pixel-axis spacing bound");
        double coverage = coverageFraction.get();
        if (!Double.isFinite(coverage) || coverage < 0 || coverage > 1)
          throw new IllegalArgumentException("Invalid coverage fraction");
      } else {
        if (unevaluatedReason.isEmpty() || unevaluatedReason.get().isBlank())
          throw new IllegalArgumentException("An unevaluated sample requires a recorded reason");
        if (footprintCorners.isPresent()
            || footprintAreaSquareMeters.isPresent()
            || maximumPixelAxisSpacingBoundMeters.isPresent()
            || exceedsMaximumGroundSampleDistance.isPresent()
            || coverageFraction.isPresent())
          throw new IllegalArgumentException(
              "An unevaluated sample must not carry footprint-derived facts");
      }
    }
  }

  /** The only scope value this contract can ever produce: no coverage duration, no feasibility. */
  public enum CameraFootprintEvaluationScope {
    SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE
  }

  /**
   * The full owner evidence document. {@code orbitPropagationModel}, {@code orbitSourceHash} and
   * {@code referenceDigest} are carried through unchanged from the owned pointing result after this
   * implementation independently re-verifies them against the owned orbit at read time -- see the
   * class javadoc. {@code cameraModelHash} and {@code planningModelHash} are {@code
   * msc.platform.Json#fingerprint} of the exact-version envelope ({@code id}/{@code version}/{@code
   * body}) this evaluation pinned, retained so a consumer can detect drift without re-fetching.
   * {@code missionDefinitionVersion} is the pinned camera model's own value, already re-checked at
   * read time to equal the pinned planning model's value.
   */
  public record ModelSnapshot<T>(String id, long version, T body) {
    public ModelSnapshot {
      text(id);
      if (version < 1) throw new IllegalArgumentException("Positive model version required");
      Objects.requireNonNull(body);
    }
  }

  public record CameraFootprintEvaluationResult(
      String id,
      String pointingResultId,
      String solutionId,
      String spacecraftId,
      String orbitPropagationModel,
      String orbitSourceHash,
      String referenceDigest,
      String missionDefinitionVersion,
      long cameraModelVersion,
      String cameraModelHash,
      long planningModelVersion,
      String planningModelHash,
      ModelSnapshot<SimulationCameraModelContracts.Model> cameraModel,
      ModelSnapshot<SimulationPlanningContracts.Model> planningModel,
      CameraFootprintEvaluationQuery query,
      AoiPlanarMapping aoiMapping,
      double maximumOffNadirDegreesLimit,
      double minimumTargetElevationDegreesLimit,
      double maximumGroundSampleDistanceMetersLimit,
      List<CameraFootprintSample> samples,
      CameraFootprintEvaluationScope scope) {
    public CameraFootprintEvaluationResult {
      text(id);
      text(pointingResultId);
      text(solutionId);
      text(spacecraftId);
      text(orbitPropagationModel);
      text(orbitSourceHash);
      text(referenceDigest);
      text(missionDefinitionVersion);
      if (cameraModelVersion < 1)
        throw new IllegalArgumentException("Exact positive camera model version required");
      text(cameraModelHash);
      if (planningModelVersion < 1)
        throw new IllegalArgumentException("Exact positive planning model version required");
      text(planningModelHash);
      Objects.requireNonNull(cameraModel);
      Objects.requireNonNull(planningModel);
      if (!spacecraftId.equals(cameraModel.id())
          || cameraModel.version() != cameraModelVersion
          || !spacecraftId.equals(planningModel.id())
          || planningModel.version() != planningModelVersion)
        throw new IllegalArgumentException("Pinned model envelope identity/version mismatch");
      Objects.requireNonNull(query);
      if (!pointingResultId.equals(query.pointingResultId()))
        throw new IllegalArgumentException("Result must bind its own query's pointing result id");
      if (cameraModelVersion != query.cameraModelVersion())
        throw new IllegalArgumentException("Result must bind its own query's camera model version");
      Objects.requireNonNull(aoiMapping);
      if (!Double.isFinite(maximumOffNadirDegreesLimit) || maximumOffNadirDegreesLimit <= 0)
        throw new IllegalArgumentException("Invalid maximum off-nadir limit");
      if (!Double.isFinite(minimumTargetElevationDegreesLimit)
          || minimumTargetElevationDegreesLimit < 0
          || minimumTargetElevationDegreesLimit >= 90)
        throw new IllegalArgumentException("Invalid minimum target elevation limit");
      if (!Double.isFinite(maximumGroundSampleDistanceMetersLimit)
          || maximumGroundSampleDistanceMetersLimit <= 0)
        throw new IllegalArgumentException("Invalid maximum ground sample distance limit");
      samples = List.copyOf(samples);
      if (samples.isEmpty())
        throw new IllegalArgumentException("Empty camera footprint evaluation");
      Objects.requireNonNull(scope);
    }
  }
}
