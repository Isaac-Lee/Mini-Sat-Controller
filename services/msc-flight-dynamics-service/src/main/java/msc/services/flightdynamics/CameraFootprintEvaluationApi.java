package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import msc.contracts.CameraFootprintEvaluationContracts.AoiPlanarMapping;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationQuery;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationResult;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationScope;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintSample;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintSample.SampleOutcome;
import msc.contracts.CameraFootprintEvaluationContracts.ModelSnapshot;
import msc.contracts.CameraFootprintEvaluationContracts.Point;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.PointingContracts.RequiredTargetPointingSample;
import msc.contracts.SimulationCameraModelContracts;
import msc.contracts.SimulationPlanningContracts;
import msc.contracts.TaskingContracts;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.orbit.GeneralPerturbationsAdapter;
import msc.orbit.KeplerianOrbitAdapter;
import msc.orbit.RequiredTargetPointingCalculator;
import msc.orbit.StareCameraProjection;
import msc.orbit.StareCameraProjection.Camera;
import msc.orbit.StareCameraProjection.Rectangle;
import msc.platform.*;
import org.orekit.utils.Constants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/**
 * Computes sampled camera evidence from an owned pointing result and exact owner model versions.
 */
@RestController
public class CameraFootprintEvaluationApi {
  /**
   * Explicit cap on the number of owned pointing samples one evaluation request will process. This
   * endpoint's own operational bound; it governs independent of any bound the referenced pointing
   * profile's own query already applied. Exceeding it is a request error (400), never a silent
   * truncation of the evaluated samples.
   */
  public static final int MAXIMUM_EVALUATED_SAMPLES = 2000;

  public static final int MAXIMUM_RESULT_BYTES = 16 * 1024 * 1024;

  private final StateStore store;
  private final ServiceHttp http;
  private final ObjectStorage objects;
  private final Json json;

  public CameraFootprintEvaluationApi(
      StateStore store, ServiceHttp http, ObjectStorage objects, Json json) {
    this.store = store;
    this.http = http;
    this.objects = objects;
    this.json = json;
  }

  public record Request(CameraFootprintEvaluationQuery query) {
    public Request {
      Objects.requireNonNull(query);
    }
  }

  /**
   * Small, persisted projection of {@link CameraFootprintEvaluationResult}: identity, pinned
   * version hashes, counts and the {@code objectReference} for the full evidence document. Never
   * embeds the per-sample list itself -- see {@code GET .../result}.
   */
  public record Manifest(
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
      CameraFootprintEvaluationQuery query,
      AoiPlanarMapping aoiMapping,
      double maximumOffNadirDegreesLimit,
      double minimumTargetElevationDegreesLimit,
      double maximumGroundSampleDistanceMetersLimit,
      int sampleCount,
      int computedSampleCount,
      int unevaluatedSampleCount,
      CameraFootprintEvaluationScope scope,
      String objectReference) {}

  @PostMapping({"/api/camera-footprint-evaluations", "/internal/camera-footprint-evaluations"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode evaluate(
      @RequestBody Request request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    String scope = "camera-footprint-evaluation:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();

    var query = request.query();
    var pointing =
        store
            .find(
                "required-target-pointing",
                query.pointingResultId(),
                RequiredTargetPointingResult.class)
            .orElseThrow(() -> ApiException.missing("Owned pointing result not found"))
            .body();

    // Bound the computation size before any owner revalidation or external HTTP call: a request
    // this endpoint will reject outright should not first pay for a wasted mission-definition
    // round trip.

    if (pointing.samples().size() > MAXIMUM_EVALUATED_SAMPLES)
      throw ApiException.invalid("Camera evaluation supports at most 2000 owned pointing samples");

    var cartesian = store.find("orbit", pointing.solutionId(), InitialState.class);
    var gp = store.find("public-orbit", pointing.solutionId(), Snapshot.class);
    if (cartesian.isPresent() && gp.isPresent())
      throw ApiException.conflict("Ambiguous orbit solution identity");
    String spacecraftId;
    String orbitPropagationModel;
    String orbitSourceHash;
    if (cartesian.isPresent()) {
      var initial = cartesian.get().body();
      if (!pointing.solutionId().equals(initial.solutionId()))
        throw ApiException.invalid("Orbit solution binding mismatch");
      spacecraftId = initial.spacecraftId();
      orbitPropagationModel = KeplerianOrbitAdapter.MODEL;
      orbitSourceHash = json.fingerprint(initial);
    } else if (gp.isPresent()) {
      var snapshot = gp.get().body();
      if (!pointing.solutionId().equals(snapshot.id())
          || snapshot.elements() == null
          || snapshot.rawSha256() == null
          || !snapshot.rawSha256().matches("[a-f0-9]{64}")
          || !snapshot
              .id()
              .equals("gp-" + snapshot.elements().noradId() + "-" + snapshot.rawSha256()))
        throw ApiException.invalid("Reference snapshot binding mismatch");
      spacecraftId = "norad-" + snapshot.elements().noradId();
      orbitPropagationModel = GeneralPerturbationsAdapter.MODEL;
      orbitSourceHash = snapshot.rawSha256();
    } else {
      throw ApiException.missing("Owned orbit for pointing result not found");
    }
    if (!spacecraftId.equals(pointing.spacecraftId())
        || !orbitPropagationModel.equals(pointing.orbitPropagationModel())
        || !orbitSourceHash.equals(pointing.orbitSourceHash()))
      throw ApiException.invalid(
          "Owned pointing result no longer matches its own source orbit identity");

    // Fetch the exact pinned camera model version -- never "current".
    var cameraEnvelope =
        http.get(
            "mission-definition",
            "/internal/simulation-camera-models/"
                + UriUtils.encodePathSegment(spacecraftId, StandardCharsets.UTF_8)
                + "/versions/"
                + query.cameraModelVersion(),
            JsonNode.class);
    if (!spacecraftId.equals(cameraEnvelope.path("id").asText())
        || !cameraEnvelope.path("version").isIntegralNumber()
        || !cameraEnvelope.path("version").canConvertToLong()
        || cameraEnvelope.path("version").longValue() != query.cameraModelVersion())
      throw ApiException.invalid("Camera model owner identity/version mismatch");
    var camera =
        json.convert(cameraEnvelope.get("body"), SimulationCameraModelContracts.Model.class);
    if (!spacecraftId.equals(camera.spacecraftId()))
      throw ApiException.invalid("Camera model spacecraft mismatch");
    var cameraEnvelopeState =
        new StateStore.State<>(spacecraftId, query.cameraModelVersion(), camera);
    String cameraModelHash = json.fingerprint(cameraEnvelopeState);

    // Fetch the exact planning model version the camera model itself pins, re-checked rather
    // than assumed to still bind the same spacecraft and mission definition (mirroring
    // SimulationCameraModelApi.publish's own binding check, repeated here at read time).
    var planningEnvelope =
        http.get(
            "mission-definition",
            "/internal/simulation-planning-models/"
                + UriUtils.encodePathSegment(spacecraftId, StandardCharsets.UTF_8)
                + "/versions/"
                + camera.simulationPlanningModelVersion(),
            JsonNode.class);
    if (!spacecraftId.equals(planningEnvelope.path("id").asText())
        || !planningEnvelope.path("version").isIntegralNumber()
        || !planningEnvelope.path("version").canConvertToLong()
        || planningEnvelope.path("version").longValue() != camera.simulationPlanningModelVersion())
      throw ApiException.invalid("Planning model owner identity/version mismatch");
    var planning =
        json.convert(planningEnvelope.get("body"), SimulationPlanningContracts.Model.class);
    if (!spacecraftId.equals(planning.spacecraftId())
        || !camera.missionDefinitionVersion().equals(planning.missionDefinitionVersion()))
      throw ApiException.invalid(
          "Pinned planning model no longer binds the same spacecraft and mission definition");
    var planningEnvelopeState =
        new StateStore.State<>(spacecraftId, camera.simulationPlanningModelVersion(), planning);
    String planningModelHash = json.fingerprint(planningEnvelopeState);

    if (camera.maximumOffNadirDegrees() > planning.maximumOffNadirDegrees())
      throw ApiException.invalid("Camera off-nadir limit exceeds its pinned planning model");
    if (!RequiredTargetPointingCalculator.POINTING_MODEL.equals(pointing.pointingModel()))
      throw ApiException.invalid("Unsupported owned pointing model");
    for (var sample : pointing.samples()) {
      if (!sample.target().equals(pointing.query().target()))
        throw ApiException.invalid("Owned pointing sample target differs from the query target");
    }

    var aoiMapping = mapAoi(pointing.query().target(), query.area());
    var rectangle =
        new Rectangle(
            -aoiMapping.widthMeters() / 2,
            aoiMapping.widthMeters() / 2,
            -aoiMapping.heightMeters() / 2,
            aoiMapping.heightMeters() / 2);
    var cameraGeometry =
        new Camera(
            camera.halfAngleAcrossDegrees(),
            camera.halfAngleAlongDegrees(),
            camera.rasterColumns(),
            camera.rasterRows());

    var samples = new ArrayList<CameraFootprintSample>(pointing.samples().size());
    for (RequiredTargetPointingSample s : pointing.samples())
      samples.add(evaluateSample(s, cameraGeometry, rectangle, camera));

    String id = UUID.randomUUID().toString();
    var result =
        new CameraFootprintEvaluationResult(
            id,
            query.pointingResultId(),
            pointing.solutionId(),
            spacecraftId,
            orbitPropagationModel,
            orbitSourceHash,
            pointing.referenceDigest(),
            camera.missionDefinitionVersion(),
            query.cameraModelVersion(),
            cameraModelHash,
            camera.simulationPlanningModelVersion(),
            planningModelHash,
            new ModelSnapshot<>(spacecraftId, query.cameraModelVersion(), camera),
            new ModelSnapshot<>(spacecraftId, camera.simulationPlanningModelVersion(), planning),
            query,
            aoiMapping,
            camera.maximumOffNadirDegrees(),
            camera.minimumTargetElevationDegrees(),
            camera.maximumGroundSampleDistanceMeters(),
            List.copyOf(samples),
            CameraFootprintEvaluationScope.SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE);

    // Bulk evidence document lives in object storage; the persisted state/event manifest stays
    // small. Written before the DB transaction, mirroring OrbitApi.predict: a content-addressed
    // write can safely leave an orphan after a database failure, and history never references an
    // unfinished object.
    byte[] resultBytes = json.write(result).getBytes(StandardCharsets.UTF_8);
    if (resultBytes.length > MAXIMUM_RESULT_BYTES)
      throw ApiException.invalid("Camera evidence exceeds the 16 MiB result limit");
    objects.ensureBucket();
    String objectReference;
    try (var input = new ByteArrayInputStream(resultBytes)) {
      objectReference = objects.write("application/json", input);
    }

    int computedCount =
        (int) samples.stream().filter(s -> s.outcome() == SampleOutcome.COMPUTED).count();
    int unevaluatedCount = samples.size() - computedCount;
    var manifest =
        new Manifest(
            id,
            query.pointingResultId(),
            pointing.solutionId(),
            spacecraftId,
            orbitPropagationModel,
            orbitSourceHash,
            pointing.referenceDigest(),
            camera.missionDefinitionVersion(),
            query.cameraModelVersion(),
            cameraModelHash,
            camera.simulationPlanningModelVersion(),
            planningModelHash,
            query,
            aoiMapping,
            camera.maximumOffNadirDegrees(),
            camera.minimumTargetElevationDegrees(),
            camera.maximumGroundSampleDistanceMeters(),
            samples.size(),
            computedCount,
            unevaluatedCount,
            CameraFootprintEvaluationScope.SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE,
            objectReference);

    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          var saved = store.create("camera-footprint-evaluation", id, manifest);
          store.event(
              "CameraFootprintEvaluationComputed",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              manifest);
          return saved;
        });
  }

  /**
   * Applies the {@value msc.contracts.CameraFootprintEvaluationContracts#AOI_MAPPING} law: see the
   * contract class javadoc. Rejects a request whose AOI centre does not match the pointing target,
   * or whose mapped extent falls outside the declared domain -- never a silent approximation
   * outside that domain.
   */
  private AoiPlanarMapping mapAoi(Target target, TaskingContracts.Area area) {
    double centerLatitude = (area.south() + area.north()) / 2;
    double centerLongitude = (area.west() + area.east()) / 2;
    if (Math.abs(target.latitudeDegrees() - centerLatitude)
            > msc.contracts.CameraFootprintEvaluationContracts.AOI_CENTER_TOLERANCE_DEGREES
        || Math.abs(target.longitudeDegrees() - centerLongitude)
            > msc.contracts.CameraFootprintEvaluationContracts.AOI_CENTER_TOLERANCE_DEGREES)
      throw ApiException.invalid(
          "AOI centre must equal the pointing target latitude/longitude within "
              + msc.contracts.CameraFootprintEvaluationContracts.AOI_CENTER_TOLERANCE_DEGREES
              + " degrees");
    if (Math.abs(target.latitudeDegrees())
        > msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES)
      throw ApiException.invalid(
          "Target latitude "
              + target.latitudeDegrees()
              + " is outside the declared AOI mapping domain (|latitude| <= "
              + msc.contracts.CameraFootprintEvaluationContracts
                  .AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES
              + " degrees)");
    double phi = Math.toRadians(target.latitudeDegrees());
    double h = target.altitudeMeters();
    double flattening = Constants.WGS84_EARTH_FLATTENING;
    double eccentricitySquared = flattening * (2 - flattening);
    double equatorialRadius = Constants.WGS84_EARTH_EQUATORIAL_RADIUS;
    double sinPhi = Math.sin(phi);
    double denominator = 1 - eccentricitySquared * sinPhi * sinPhi;
    double primeVerticalRadius = equatorialRadius / Math.sqrt(denominator);
    double meridionalRadius =
        equatorialRadius * (1 - eccentricitySquared) / Math.pow(denominator, 1.5);
    double widthMeters =
        (primeVerticalRadius + h) * Math.cos(phi) * Math.toRadians(area.east() - area.west());
    double heightMeters = (meridionalRadius + h) * Math.toRadians(area.north() - area.south());
    if (!Double.isFinite(widthMeters)
        || widthMeters <= 0
        || widthMeters > msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_WIDTH_METERS)
      throw ApiException.invalid(
          "Mapped AOI width "
              + widthMeters
              + " m is outside the declared domain (0 < width <= "
              + msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_WIDTH_METERS
              + " m)");
    if (!Double.isFinite(heightMeters)
        || heightMeters <= 0
        || heightMeters
            > msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_HEIGHT_METERS)
      throw ApiException.invalid(
          "Mapped AOI height "
              + heightMeters
              + " m is outside the declared domain (0 < height <= "
              + msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_HEIGHT_METERS
              + " m)");
    return new AoiPlanarMapping(
        msc.contracts.CameraFootprintEvaluationContracts.AOI_MAPPING,
        "SIMULATION",
        msc.contracts.CameraFootprintEvaluationContracts.AOI_MAPPING_APPROXIMATION_NOTE,
        target.latitudeDegrees(),
        target.longitudeDegrees(),
        widthMeters,
        heightMeters,
        msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_ABSOLUTE_LATITUDE_DEGREES,
        msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_WIDTH_METERS,
        msc.contracts.CameraFootprintEvaluationContracts.AOI_MAXIMUM_HEIGHT_METERS);
  }

  /**
   * A projection failure on demanding geometry is a recorded per-sample reason, never a request
   * failure. Off-nadir/elevation comparisons are always present, independent of projection outcome,
   * since they depend only on the already-computed pointing sample and the pinned camera limits.
   * Footprint-derived facts (spacing bound, GSD-bound comparison, coverage fraction) are present
   * only when projection succeeds.
   */
  private CameraFootprintSample evaluateSample(
      RequiredTargetPointingSample s,
      Camera cameraGeometry,
      Rectangle rectangle,
      SimulationCameraModelContracts.Model camera) {
    boolean exceedsOffNadir = s.requiredOffNadirDegrees() > camera.maximumOffNadirDegrees();
    boolean belowElevation =
        s.topocentricElevationDegrees() < camera.minimumTargetElevationDegrees();
    try {
      var projection =
          new StareCameraProjection(
              cameraGeometry, s.satellitePositionEarthFixedMeters(), s.target());
      var footprint = projection.footprint();
      double coverage = projection.coverageFraction(rectangle);
      boolean exceedsGsd =
          footprint.maximumPixelAxisSpacingBoundMeters()
              > camera.maximumGroundSampleDistanceMeters();
      var corners =
          footprint.corners().stream()
              .map(c -> new Point(c.eastMeters(), c.northMeters()))
              .toList();
      return new CameraFootprintSample(
          s.instant(),
          s.requiredOffNadirDegrees(),
          exceedsOffNadir,
          s.topocentricElevationDegrees(),
          belowElevation,
          SampleOutcome.COMPUTED,
          Optional.empty(),
          Optional.of(corners),
          Optional.of(footprint.areaSquareMeters()),
          Optional.of(footprint.maximumPixelAxisSpacingBoundMeters()),
          Optional.of(exceedsGsd),
          Optional.of(coverage));
    } catch (IllegalArgumentException failure) {
      String reason =
          failure.getMessage() == null ? "Camera projection failed" : failure.getMessage();
      return new CameraFootprintSample(
          s.instant(),
          s.requiredOffNadirDegrees(),
          exceedsOffNadir,
          s.topocentricElevationDegrees(),
          belowElevation,
          SampleOutcome.UNEVALUATED,
          Optional.of(reason),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
  }

  @GetMapping({
    "/api/camera-footprint-evaluations/{id}",
    "/internal/camera-footprint-evaluations/{id}"
  })
  public Manifest manifest(@PathVariable String id) {
    return store.require("camera-footprint-evaluation", id, Manifest.class).body();
  }

  /** Streams the full evidence document (all per-sample facts) from owned object storage. */
  @GetMapping({
    "/api/camera-footprint-evaluations/{id}/result",
    "/internal/camera-footprint-evaluations/{id}/result"
  })
  public org.springframework.http.ResponseEntity<
          org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      result(@PathVariable String id) {
    var manifest = manifest(id);
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            out -> {
              try (var input = objects.read(manifest.objectReference())) {
                input.transferTo(out);
              }
            });
  }
}
