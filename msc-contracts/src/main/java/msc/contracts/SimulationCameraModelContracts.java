package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.Objects;

/**
 * Explicit ADMIN-published, versioned SIMULATION camera model for a spacecraft, bound to a mission
 * definition and to a pinned version of {@link SimulationPlanningContracts.Model}. Owned by Mission
 * Definition, one document per {@code spacecraftId}. See {@code
 * docs/backend/simulation-camera-model.md} for rationale; this header states only what a caller
 * must know to use the contract correctly.
 *
 * <p><b>Law:</b> exactly one pointing law is supported, {@link
 * PointingLaw#STARE_TARGET_TANGENT_PLANE_V1}. Given a satellite position and a fixed WGS84 geodetic
 * target: <b>boresight</b> = satellite-to-target unit vector; <b>along-axis</b> = geodetic north
 * projected into the plane perpendicular to the boresight, normalized ({@code north - (north ·
 * boresight) * boresight}, normalized); <b>across-axis</b> = {@code along-axis × boresight}, giving
 * the right-handed basis {@code (across, along, boresight)}, verified by {@code across-axis ×
 * along-axis = boresight}. This is a declared synthetic law, not attitude inferred from one line of
 * sight.
 *
 * <p><b>Degeneracy:</b> rejected whenever the acute angle between the boresight and the geodetic
 * north axis (the undirected line, so both the parallel and antiparallel cases are caught alike) is
 * below {@link #MINIMUM_NORTH_BORESIGHT_SEPARATION_DEGREES}. Equivalently: {@code |north ·
 * boresight| > cos(threshold)}, or {@code ‖north − (north · boresight) · boresight‖ <
 * sin(threshold)} — both vanish at 0° and 180° alike; an evaluator may use either form.
 *
 * <p><b>Raster:</b> a future evaluator's per-pixel rays are uniform and rectilinear in the pinhole
 * tangent/focal-plane coordinates {@code (x, y)} where a ray is {@code boresight + x · across + y ·
 * along} — <b>not</b> uniform in angle, and <b>not</b> uniform on the ground.
 *
 * <p><b>Fields:</b> {@code environment} must equal {@code "SIMULATION"}. {@code
 * halfAngleAcrossDegrees}/{@code halfAngleAlongDegrees} are half-angles in degrees, {@code (0,
 * MAXIMUM_HALF_ANGLE_DEGREES]}. {@code rasterColumns}/{@code rasterRows} are pixel counts, {@code
 * [1, MAXIMUM_RASTER_DIMENSION]}. {@code maximumGroundSampleDistanceMeters} is an acceptability
 * ceiling only — no ground-sample-distance-at-nadir is declared; actual GSD is always derived later
 * from projected pixel rays. {@code maximumOffNadirDegrees} is {@code (0, 60]} and is additionally
 * checked at publish against the pinned planning model (see below). {@code
 * minimumTargetElevationDegrees} is {@code [0, 90)}. These published bounds are deliberately
 * narrower than the pure numerical projection helper's own bounds; the helper is a lower-level
 * primitive, while these are what a consumer of this published model actually binds to.
 *
 * <p><b>Binding:</b> {@code missionDefinitionVersion} must match the spacecraft's stored {@code
 * CatalogContracts.MissionProfile}. {@code simulationPlanningModelVersion} pins the exact stored
 * version of the spacecraft's current {@link SimulationPlanningContracts.Model}; {@code
 * SimulationCameraModelApi.publish} rejects the request unless that pin equals the resolved state's
 * version exactly, and rejects {@code maximumOffNadirDegrees} exceeding the resolved model's own
 * bound. Neither this model's off-nadir bound nor any other field is copied from the pinned model.
 *
 * <p><b>Scope:</b> this is the camera model only, SIMULATION-scoped throughout. It computes no
 * footprint, no ground sample distance, and establishes no coverage or feasibility; {@code
 * AOI_SENSOR_COVERAGE} stays {@code NOT_EVALUATED} until a separate evaluator exists.
 */
public final class SimulationCameraModelContracts {
  private SimulationCameraModelContracts() {}

  /**
   * Minimum acute angle, in degrees, between the boresight and the geodetic north axis required for
   * the along-axis projection in the declared orientation law to be well-defined. See the class
   * javadoc's "Degeneracy" paragraph for the two equivalent numeric forms. This is a property of
   * the declared law, not a per-document parameter.
   */
  public static final double MINIMUM_NORTH_BORESIGHT_SEPARATION_DEGREES = 1.0;

  /**
   * Upper bound on a declared camera half-angle, in degrees; an implementation/validation bound.
   */
  public static final double MAXIMUM_HALF_ANGLE_DEGREES = 45.0;

  /** Upper bound on a declared raster dimension, in pixels; an implementation/validation bound. */
  public static final int MAXIMUM_RASTER_DIMENSION = 50_000;

  /**
   * Upper bound on the declared acceptability ceiling for ground sample distance, in meters; an
   * implementation/validation bound, not a hardware specification.
   */
  public static final double MAXIMUM_GROUND_SAMPLE_DISTANCE_CEILING_METERS = 10_000;

  /**
   * Supported synthetic camera pointing laws. Exactly one value is supported; no value exists here
   * solely to be declared and refused.
   */
  public enum PointingLaw {
    /** Fixed ground-target staring geometry with the class javadoc's declared orientation law. */
    STARE_TARGET_TANGENT_PLANE_V1
  }

  /**
   * One SIMULATION camera model. See the class javadoc for the declared orientation and evaluation
   * law this model's fields feed into.
   *
   * @param halfAngleAcrossDegrees Half of the camera's across-track field of view, in degrees;
   *     finite, in {@code (0, MAXIMUM_HALF_ANGLE_DEGREES]}.
   * @param halfAngleAlongDegrees Half of the camera's along-track field of view, in degrees;
   *     finite, in {@code (0, MAXIMUM_HALF_ANGLE_DEGREES]}.
   * @param rasterColumns Pixel sampling across the sensor's across-track extent; a positive
   *     integer, at most {@code MAXIMUM_RASTER_DIMENSION}.
   * @param rasterRows Pixel sampling across the sensor's along-track extent; a positive integer, at
   *     most {@code MAXIMUM_RASTER_DIMENSION}.
   * @param maximumGroundSampleDistanceMeters The acceptability ceiling on ground sample distance;
   *     finite, in {@code (0, MAXIMUM_GROUND_SAMPLE_DISTANCE_CEILING_METERS]}. Not a declared value
   *     at nadir; see the class javadoc.
   * @param maximumOffNadirDegrees Finite, in {@code (0, 60]}, and validated at publish to be no
   *     greater than the pinned {@link SimulationPlanningContracts.Model#maximumOffNadirDegrees()}
   *     (see {@code simulationPlanningModelVersion}).
   * @param minimumTargetElevationDegrees Minimum topocentric elevation of the satellite as seen
   *     from the target, measured positive above the horizon; finite, in {@code [0, 90)}.
   * @param simulationPlanningModelVersion The exact stored version of the spacecraft's current
   *     {@link SimulationPlanningContracts.Model} this camera model was validated against, pinned
   *     by an ADMIN at publish time. Never a caller-supplied geometry value: {@code
   *     SimulationCameraModelApi.publish} independently resolves the spacecraft's current
   *     simulation planning model and rejects the request unless this pin equals its stored version
   *     exactly.
   */
  public record Model(
      String spacecraftId,
      String missionDefinitionVersion,
      String environment,
      PointingLaw pointingLaw,
      double halfAngleAcrossDegrees,
      double halfAngleAlongDegrees,
      int rasterColumns,
      int rasterRows,
      double maximumGroundSampleDistanceMeters,
      double maximumOffNadirDegrees,
      double minimumTargetElevationDegrees,
      long simulationPlanningModelVersion,
      String approvalReference,
      String provenance) {
    public Model {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(approvalReference);
      text(provenance);
      Objects.requireNonNull(pointingLaw, "Pointing law is required");
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException("Only explicitly simulated camera models are supported");
      if (!Double.isFinite(halfAngleAcrossDegrees)
          || halfAngleAcrossDegrees <= 0
          || halfAngleAcrossDegrees > MAXIMUM_HALF_ANGLE_DEGREES)
        throw new IllegalArgumentException("Invalid across-track half-angle");
      if (!Double.isFinite(halfAngleAlongDegrees)
          || halfAngleAlongDegrees <= 0
          || halfAngleAlongDegrees > MAXIMUM_HALF_ANGLE_DEGREES)
        throw new IllegalArgumentException("Invalid along-track half-angle");
      if (rasterColumns < 1 || rasterColumns > MAXIMUM_RASTER_DIMENSION)
        throw new IllegalArgumentException("Invalid raster column count");
      if (rasterRows < 1 || rasterRows > MAXIMUM_RASTER_DIMENSION)
        throw new IllegalArgumentException("Invalid raster row count");
      if (!Double.isFinite(maximumGroundSampleDistanceMeters)
          || maximumGroundSampleDistanceMeters <= 0
          || maximumGroundSampleDistanceMeters > MAXIMUM_GROUND_SAMPLE_DISTANCE_CEILING_METERS)
        throw new IllegalArgumentException("Invalid maximum ground sample distance");
      if (!Double.isFinite(maximumOffNadirDegrees)
          || maximumOffNadirDegrees <= 0
          || maximumOffNadirDegrees > 60)
        throw new IllegalArgumentException("Invalid maximum off-nadir bound");
      if (!Double.isFinite(minimumTargetElevationDegrees)
          || minimumTargetElevationDegrees < 0
          || minimumTargetElevationDegrees >= 90)
        throw new IllegalArgumentException("Invalid minimum target elevation bound");
      if (simulationPlanningModelVersion < 1)
        throw new IllegalArgumentException(
            "A pinned simulation planning model version is required");
    }
  }

  public record Publish(long expectedVersion, Model model) {
    public Publish {
      if (expectedVersion < 0 || model == null)
        throw new IllegalArgumentException("Expected version and model are required");
    }
  }
}
