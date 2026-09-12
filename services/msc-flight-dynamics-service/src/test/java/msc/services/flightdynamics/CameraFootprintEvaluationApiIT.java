package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationQuery;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationResult;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintEvaluationScope;
import msc.contracts.CameraFootprintEvaluationContracts.CameraFootprintSample.SampleOutcome;
import msc.contracts.PointingContracts.RequiredTargetPointingQuery;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.PointingContracts.RequiredTargetPointingSample;
import msc.contracts.PointingContracts.RequiredTargetPointingScope;
import msc.contracts.SimulationCameraModelContracts;
import msc.contracts.SimulationPlanningContracts;
import msc.contracts.TaskingContracts;
import msc.domain.anomaly.MissionPhase;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.Trajectory;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.flightdynamics.Trajectory.Vector;
import msc.domain.time.MissionDuration;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;
import msc.orbit.KeplerianOrbitAdapter;
import msc.orbit.StareCameraProjection;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.orekit.utils.Constants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real DB behaviour for {@link CameraFootprintEvaluationApi}, calling the controller method
 * directly (like {@code RequiredTargetPointingApiIT}) against a real Testcontainers Postgres and
 * real Flyway-applied schema. The cross-service Mission Definition fetch is exercised through a
 * mocked {@code ServiceHttp}, mirroring the only existing precedent for testing a {@code
 * ServiceHttp}-consuming Flight Dynamics/Planning API in this codebase ({@code
 * PlanningIlluminationApiTest}); this suite does not stand up a second live HTTP service. Object
 * storage is a Mockito mock backed by an in-memory map, mirroring {@code SimulationProductTest}.
 *
 * <p>Geometry fixtures reuse the same nadir/oblique/degenerate numeric cases already proven in
 * {@code StareCameraProjectionTest} (root-owned, not re-derived here); this suite verifies this
 * API's wiring, revalidation and persistence, not the projection helper's own geometric
 * correctness.
 */
@Testcontainers
class CameraFootprintEvaluationApiIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  private static final double MU = 3.986004418e14;
  private static final double HEIGHT = 500_000;
  private static final Target NADIR_TARGET = new Target("camera-eval-nadir-target", 0, 0, 0);

  StateStore store;
  Json json;
  JdbcTemplate db;
  ServiceHttp http;
  ObjectStorage objects;
  CameraFootprintEvaluationApi api;
  MissionInstant now;
  final Authentication actor = new UsernamePasswordAuthenticationToken("operator1", "unused");
  final Map<String, byte[]> fakeObjectStore = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    now = MissionInstant.tai(2_000_000_000L);
    store =
        new StateStore(
            new JdbcTemplate(ds),
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> now);
    http = mock(ServiceHttp.class);
    objects = mock(ObjectStorage.class);
    fakeObjectStore.clear();
    when(objects.write(eq("application/json"), any()))
        .thenAnswer(
            invocation -> {
              byte[] bytes = ((InputStream) invocation.getArgument(1)).readAllBytes();
              String reference = "s3://camera-eval-test/" + UUID.randomUUID();
              fakeObjectStore.put(reference, bytes);
              return reference;
            });
    when(objects.read(anyString()))
        .thenAnswer(
            invocation -> new ByteArrayInputStream(fakeObjectStore.get(invocation.getArgument(0))));
    api = new CameraFootprintEvaluationApi(store, http, objects, json);
  }

  // ---- Fixture builders -------------------------------------------------------------------

  private InitialState orbit(String solutionId, String spacecraftId) {
    return new InitialState(
        solutionId,
        spacecraftId,
        now,
        new Vector(7_000_000, 0, 0),
        new Vector(0, Math.sqrt(MU / 7_000_000), 0),
        "Synthetic camera-evaluation orbit fixture");
  }

  private RequiredTargetPointingSample sample(
      MissionInstant instant,
      Vector satelliteEarthFixedMeters,
      Target target,
      double offNadirDegrees,
      double elevationDegrees) {
    return new RequiredTargetPointingSample(
        instant,
        satelliteEarthFixedMeters,
        new Vector(1, 0, 0),
        new Vector(1, 0, 0),
        offNadirDegrees,
        500_000,
        new Trajectory.GroundPoint(
            instant, target.latitudeDegrees(), target.longitudeDegrees(), 0, "test-fixture"),
        target,
        elevationDegrees,
        0,
        elevationDegrees >= 0);
  }

  private RequiredTargetPointingResult pointingResult(
      String solutionId,
      String spacecraftId,
      String orbitSourceHash,
      Target target,
      List<RequiredTargetPointingSample> samples) {
    var horizon = new TimeWindow(now, now.plus(new MissionDuration(600_000_000_000L)));
    var query = new RequiredTargetPointingQuery(target, horizon, 60, 0);
    return new RequiredTargetPointingResult(
        solutionId,
        spacecraftId,
        KeplerianOrbitAdapter.MODEL,
        msc.orbit.RequiredTargetPointingCalculator.POINTING_MODEL,
        orbitSourceHash,
        "test-eop-reference-digest",
        query,
        samples,
        RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE);
  }

  private SimulationCameraModelContracts.Model camera(
      String craft,
      String missionDefinitionVersion,
      double halfAngleAcross,
      double halfAngleAlong,
      int columns,
      int rows,
      double maximumGsd,
      double maximumOffNadir,
      double minimumElevation,
      long planningVersion) {
    return new SimulationCameraModelContracts.Model(
        craft,
        missionDefinitionVersion,
        "SIMULATION",
        SimulationCameraModelContracts.PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
        halfAngleAcross,
        halfAngleAlong,
        columns,
        rows,
        maximumGsd,
        maximumOffNadir,
        minimumElevation,
        planningVersion,
        "approval-camera-eval",
        "synthetic-test-fixture");
  }

  private SimulationPlanningContracts.Model planning(
      String craft, String missionDefinitionVersion, double maximumOffNadir) {
    return new SimulationPlanningContracts.Model(
        craft,
        missionDefinitionVersion,
        "SIMULATION",
        MissionPhase.ROUTINE,
        "NOMINAL",
        12_000,
        5,
        maximumOffNadir,
        20,
        true,
        40,
        200,
        50,
        0.4,
        0.5,
        "test-approved");
  }

  private JsonNode envelope(String id, long version, Object body) {
    return json.tree(new StateStore.State<>(id, version, body));
  }

  private void stubMissionDefinition(
      String craft,
      long cameraVersion,
      SimulationCameraModelContracts.Model cameraModel,
      long planningVersion,
      SimulationPlanningContracts.Model planningModel) {
    when(http.get(
            eq("mission-definition"),
            eq("/internal/simulation-camera-models/" + craft + "/versions/" + cameraVersion),
            eq(JsonNode.class)))
        .thenReturn(envelope(craft, cameraVersion, cameraModel));
    when(http.get(
            eq("mission-definition"),
            eq("/internal/simulation-planning-models/" + craft + "/versions/" + planningVersion),
            eq(JsonNode.class)))
        .thenReturn(envelope(craft, planningVersion, planningModel));
  }

  /** Converts a desired tangent-plane extent, at the equator/prime-meridian target, to an Area. */
  private TaskingContracts.Area areaForMeters(double widthMeters, double heightMeters) {
    double equatorialRadius = Constants.WGS84_EARTH_EQUATORIAL_RADIUS;
    double flattening = Constants.WGS84_EARTH_FLATTENING;
    double eccentricitySquared = flattening * (2 - flattening);
    double primeVerticalRadiusAtEquator = equatorialRadius;
    double meridionalRadiusAtEquator = equatorialRadius * (1 - eccentricitySquared);
    double dLonDegrees = Math.toDegrees(widthMeters / primeVerticalRadiusAtEquator);
    double dLatDegrees = Math.toDegrees(heightMeters / meridionalRadiusAtEquator);
    return new TaskingContracts.Area(
        "aoi-" + UUID.randomUUID(),
        -dLonDegrees / 2,
        -dLatDegrees / 2,
        dLonDegrees / 2,
        dLatDegrees / 2,
        "test-fixture");
  }

  private record Chain(String craft, String pointingId, String solutionId) {}

  private Chain seedChain(
      Target target,
      List<RequiredTargetPointingSample> samples,
      SimulationCameraModelContracts.Model cameraModel,
      long cameraVersion,
      SimulationPlanningContracts.Model planningModel,
      long planningVersion) {
    String craft = cameraModel.spacecraftId();
    String solutionId = "orbit-" + UUID.randomUUID();
    var initial = orbit(solutionId, craft);
    store.transaction(() -> store.create("orbit", solutionId, initial));
    String orbitSourceHash = json.fingerprint(initial);
    var pointing = pointingResult(solutionId, craft, orbitSourceHash, target, samples);
    String pointingId = "pointing-" + UUID.randomUUID();
    store.transaction(() -> store.create("required-target-pointing", pointingId, pointing));
    stubMissionDefinition(craft, cameraVersion, cameraModel, planningVersion, planningModel);
    return new Chain(craft, pointingId, solutionId);
  }

  private JsonNode post(CameraFootprintEvaluationApi.Request request, String key) throws Exception {
    return api.evaluate(request, key, actor);
  }

  private CameraFootprintEvaluationResult streamedResult(String id) throws Exception {
    var response = api.result(id);
    var out = new ByteArrayOutputStream();
    response.getBody().writeTo(out);
    return json.read(out.toString(StandardCharsets.UTF_8), CameraFootprintEvaluationResult.class);
  }

  private int countRows(String sql, Object... args) {
    return db.queryForObject(sql, Integer.class, args);
  }

  // ---- Planar coverage ---------------------------------------------------------------------

  @Test
  void fullCoverageWhenAoiFitsInsideFootprint() throws Exception {
    String craft = "cam-eval-full-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);

    // A 10m x 10m AOI is far smaller than the nadir footprint (~17km x ~35km at this geometry).
    var area = areaForMeters(10, 10);
    var query = new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area);
    var response = post(new CameraFootprintEvaluationApi.Request(query), "full-cov-key");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    assertEquals(1, manifest.sampleCount());
    assertEquals(1, manifest.computedSampleCount());
    assertEquals(0, manifest.unevaluatedSampleCount());

    var result = streamedResult(manifest.id());
    assertEquals(chain.pointingId(), result.pointingResultId());
    assertEquals(chain.solutionId(), result.solutionId());
    assertEquals(chain.craft(), result.spacecraftId());
    var evaluated = result.samples().getFirst();
    assertEquals(SampleOutcome.COMPUTED, evaluated.outcome());
    assertEquals(1.0, evaluated.coverageFraction().orElseThrow(), 1e-9);
  }

  @Test
  void partialCoverageMatchesIndependentGeometricRatio() throws Exception {
    String craft = "cam-eval-partial-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);

    // AOI is 2x the footprint's full width and half the footprint's full height: the intersection
    // equals the footprint's width times the AOI's own height, over the AOI's own area -- an
    // exact 0.5 ratio, independent of the exact tangent/height values (see handoff derivation).
    double footprintFullWidth = 2 * HEIGHT * Math.tan(Math.toRadians(1));
    double aoiWidth = 2 * footprintFullWidth;
    double aoiHeight = HEIGHT * Math.tan(Math.toRadians(2));
    var area = areaForMeters(aoiWidth, aoiHeight);
    var query = new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area);
    var response = post(new CameraFootprintEvaluationApi.Request(query), "partial-cov-key");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    var result = streamedResult(manifest.id());
    var evaluated = result.samples().getFirst();
    assertEquals(SampleOutcome.COMPUTED, evaluated.outcome());
    assertEquals(0.5, evaluated.coverageFraction().orElseThrow(), 1e-6);
  }

  // The AOI mapping law forces the AOI centre to equal the pointing target, and the target is
  // always the exact projection of the camera's own boresight ray -- always strictly inside a
  // successfully computed footprint. Exactly-zero coverage is therefore structurally unreachable
  // whenever outcome == COMPUTED; this proves coverage approaches (never claims to reach) zero as
  // the AOI area comes to dominate a tiny footprint, which is the honest form of a "no coverage"
  // case here.
  @Test
  void coverageApproachesZeroWhenFootprintIsTinyRelativeToAoi() throws Exception {
    String craft = "cam-eval-zero-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    // A very narrow field of view keeps the footprint tiny relative to the AOI domain ceiling.
    var cameraModel = camera(craft, "sim-v1", .01, .01, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);

    var area = areaForMeters(50_000, 50_000);
    var query = new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area);
    var response = post(new CameraFootprintEvaluationApi.Request(query), "near-zero-cov-key");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    var result = streamedResult(manifest.id());
    var evaluated = result.samples().getFirst();
    assertEquals(SampleOutcome.COMPUTED, evaluated.outcome());
    assertTrue(
        evaluated.coverageFraction().orElseThrow() > 0, "Coverage cannot be exactly zero here");
    assertTrue(evaluated.coverageFraction().orElseThrow() < 1e-4);
  }

  // ---- Oblique geometry and the GSD-bound comparison ---------------------------------------

  @Test
  void obliqueFootprintWiringAndGsdBoundComparison() throws Exception {
    String craft = "cam-eval-oblique-" + UUID.randomUUID();
    double eastOffset = 300_000;
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, eastOffset, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 10, 45));

    // Independently compute the expected footprint/spacing directly against the same trusted,
    // already-tested projection helper this API wires -- proving this API's wiring, not
    // re-deriving StareCameraProjection's own geometric correctness (see
    // StareCameraProjectionTest).
    var expected =
        new StareCameraProjection(
                new StareCameraProjection.Camera(4, 3, 32, 24), satellite, NADIR_TARGET)
            .footprint();

    // Ceiling below the actual bound -> exceeds; ceiling above -> does not.
    var tightCamera = camera(craft, "sim-v1", 4, 3, 32, 24, 1, 60, 0, 1);
    var planningModel = planning(craft, "sim-v1", 60);
    var chain = seedChain(NADIR_TARGET, samples, tightCamera, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var tightResponse =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
            "oblique-tight-key");
    var tightManifest =
        json.convert(tightResponse.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    var tightSample = streamedResult(tightManifest.id()).samples().getFirst();
    assertEquals(SampleOutcome.COMPUTED, tightSample.outcome());
    assertEquals(
        expected.areaSquareMeters(), tightSample.footprintAreaSquareMeters().orElseThrow(), 1e-3);
    assertEquals(
        expected.maximumPixelAxisSpacingBoundMeters(),
        tightSample.maximumPixelAxisSpacingBoundMeters().orElseThrow(),
        1e-6);
    assertEquals(expected.corners().size(), tightSample.footprintCorners().orElseThrow().size());
    assertTrue(tightSample.exceedsMaximumGroundSampleDistance().orElseThrow());

    String craft2 = "cam-eval-oblique2-" + UUID.randomUUID();
    var looseCamera =
        camera(
            craft2,
            "sim-v1",
            4,
            3,
            32,
            24,
            expected.maximumPixelAxisSpacingBoundMeters() + 1,
            60,
            0,
            1);
    var planningModel2 = planning(craft2, "sim-v1", 60);
    var samples2 =
        List.of(
            sample(
                now,
                new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, eastOffset, 0),
                NADIR_TARGET,
                10,
                45));
    var chain2 = seedChain(NADIR_TARGET, samples2, looseCamera, 1, planningModel2, 1);
    var looseResponse =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain2.pointingId(), 1, area)),
            "oblique-loose-key");
    var looseManifest =
        json.convert(looseResponse.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    var looseSample = streamedResult(looseManifest.id()).samples().getFirst();
    assertFalse(looseSample.exceedsMaximumGroundSampleDistance().orElseThrow());
  }

  // ---- Difficult geometry: recorded, never a request failure --------------------------------

  @Test
  void degenerateGeometryIsRecordedUnevaluatedNotRejectedAndCountsAreReported() throws Exception {
    String craft = "cam-eval-degenerate-" + UUID.randomUUID();
    // Same north-axis-degeneracy fixture StareCameraProjectionTest proves throws: the boresight
    // falls within the rejection threshold of the geodetic north axis.
    var tooClose =
        new Vector(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS + 1000, 0, 1000 / Math.tan(Math.toRadians(.5)));
    var goodSatellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples =
        List.of(
            sample(now, tooClose, NADIR_TARGET, 5, 45),
            sample(
                now.plus(new MissionDuration(60_000_000_000L)),
                goodSatellite,
                NADIR_TARGET,
                0,
                90));
    var cameraModel = camera(craft, "sim-v1", .01, .01, 10, 10, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(1, 1);
    var response =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
            "degenerate-key");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    assertEquals(2, manifest.sampleCount());
    assertEquals(1, manifest.computedSampleCount());
    assertEquals(1, manifest.unevaluatedSampleCount());

    var result = streamedResult(manifest.id());
    var degenerate = result.samples().get(0);
    assertEquals(SampleOutcome.UNEVALUATED, degenerate.outcome());
    assertTrue(degenerate.unevaluatedReason().isPresent());
    assertFalse(degenerate.unevaluatedReason().get().isBlank());
    assertTrue(degenerate.footprintCorners().isEmpty());
    assertTrue(degenerate.coverageFraction().isEmpty());
    var computed = result.samples().get(1);
    assertEquals(SampleOutcome.COMPUTED, computed.outcome());
  }

  // ---- AOI mapping law: centre tolerance and declared domain ---------------------------------

  @Test
  void aoiCenterToleranceAndDomainBoundariesEnforced() throws Exception {
    String craft = "cam-eval-aoi-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);

    // Centre far outside the 1e-9 degree tolerance.
    var offCenterArea =
        new TaskingContracts.Area("aoi-off", .001, -.001, .003, .001, "test-fixture");
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(chain.pointingId(), 1, offCenterArea)),
                "aoi-off-center-key"));

    // Oversized mapped width (> 50,000 m).
    var tooWideArea = areaForMeters(60_000, 100);
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(chain.pointingId(), 1, tooWideArea)),
                "aoi-too-wide-key"));

    // Oversized mapped height (> 50,000 m).
    var tooTallArea = areaForMeters(100, 60_000);
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(chain.pointingId(), 1, tooTallArea)),
                "aoi-too-tall-key"));

    // A high-latitude target (beyond the declared |lat| <= 80 degree domain) is rejected outright.
    var highLatitudeTarget = new Target("camera-eval-high-lat", 81, 0, 0);
    var highLatSamples = List.of(sample(now, satellite, highLatitudeTarget, 0, 90));
    var highLatChain =
        seedChain(highLatitudeTarget, highLatSamples, cameraModel, 1, planningModel, 1);
    var highLatArea =
        new TaskingContracts.Area("aoi-high-lat", -.01, 80.99, .01, 81.01, "test-fixture");
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(highLatChain.pointingId(), 1, highLatArea)),
                "aoi-high-lat-key"));

    // A valid, small AOI at the same centre succeeds (positive control).
    var smallArea = areaForMeters(10, 10);
    var okResponse =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, smallArea)),
            "aoi-ok-key");
    assertNotNull(okResponse.get("body"));
  }

  // ---- Owner chain revalidation --------------------------------------------------------------

  @Test
  void ownerChainBindingViolationsAreRejected() throws Exception {
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var area = areaForMeters(10, 10);

    // (a) Camera envelope claims the wrong owner id -- a corrupted/mismatched response.
    {
      String craft = "cam-eval-wrong-id-" + UUID.randomUUID();
      var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
      var planningModel = planning(craft, "sim-v1", 30);
      var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
      when(http.get(
              eq("mission-definition"),
              eq("/internal/simulation-camera-models/" + craft + "/versions/1"),
              eq(JsonNode.class)))
          .thenReturn(envelope("some-other-craft", 1, cameraModel));
      assertThrows(
          ApiException.class,
          () ->
              post(
                  new CameraFootprintEvaluationApi.Request(
                      new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
                  "wrong-camera-id-key"));
    }

    // (b) Camera envelope reports the wrong version.
    {
      String craft = "cam-eval-wrong-version-" + UUID.randomUUID();
      var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
      var planningModel = planning(craft, "sim-v1", 30);
      var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
      when(http.get(
              eq("mission-definition"),
              eq("/internal/simulation-camera-models/" + craft + "/versions/1"),
              eq(JsonNode.class)))
          .thenReturn(envelope(craft, 2, cameraModel));
      assertThrows(
          ApiException.class,
          () ->
              post(
                  new CameraFootprintEvaluationApi.Request(
                      new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
                  "wrong-camera-version-key"));
    }

    // (c) Broken chain: the pinned planning model no longer binds the same mission definition.
    {
      String craft = "cam-eval-broken-chain-" + UUID.randomUUID();
      var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
      var driftedPlanningModel = planning(craft, "sim-v2-drifted", 30);
      var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, driftedPlanningModel, 1);
      assertThrows(
          ApiException.class,
          () ->
              post(
                  new CameraFootprintEvaluationApi.Request(
                      new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
                  "broken-chain-key"));
    }

    // (d) Ambiguous orbit identity: both a Cartesian and a public-GP row share the solution id.
    {
      String craft = "cam-eval-ambiguous-" + UUID.randomUUID();
      var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
      var planningModel = planning(craft, "sim-v1", 30);
      var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
      var elements =
          new msc.domain.flightdynamics.MeanElements(
              63229,
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
      var snapshot =
          new msc.contracts.OrbitReferenceContracts.Snapshot(
              chain.solutionId(),
              elements,
              "test-fixture-provider",
              "https://example.invalid/not-a-real-source",
              now,
              "a".repeat(64),
              "{}");
      store.transaction(() -> store.create("public-orbit", chain.solutionId(), snapshot));
      assertThrows(
          ApiException.class,
          () ->
              post(
                  new CameraFootprintEvaluationApi.Request(
                      new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
                  "ambiguous-orbit-key"));
    }
  }

  @Test
  void cameraLimitAndSampleTargetMustMatchPinnedSources() {
    String craft = "cam-eval-corrupt-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var badLimit =
        seedChain(NADIR_TARGET, samples, cameraModel, 1, planning(craft, "sim-v1", 20), 1);
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(
                        badLimit.pointingId(), 1, areaForMeters(10, 10))),
                "bad-limit"));
    var otherTarget = new Target("different-target", 1, 0, 0);
    var wrongTarget =
        seedChain(
            NADIR_TARGET,
            List.of(sample(now, satellite, otherTarget, 0, 90)),
            cameraModel,
            1,
            planning(craft, "sim-v1", 30),
            1);
    assertThrows(
        ApiException.class,
        () ->
            post(
                new CameraFootprintEvaluationApi.Request(
                    new CameraFootprintEvaluationQuery(
                        wrongTarget.pointingId(), 1, areaForMeters(10, 10))),
                "bad-target"));
    assertEquals(
        0, countRows("SELECT count(*) FROM state_head WHERE kind='camera-footprint-evaluation'"));
  }

  @Test
  void missingOwnedPointingResultIsNotFound() {
    var area = areaForMeters(10, 10);
    var request =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery("no-such-pointing-" + UUID.randomUUID(), 1, area));
    var thrown = assertThrows(ApiException.class, () -> post(request, "missing-key"));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, thrown.status());
  }

  // ---- Explicit computation bound ------------------------------------------------------------

  @Test
  void sampleCountCapRejectsOversizedPointingProfile() {
    String craft = "cam-eval-cap-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var oversized = new ArrayList<RequiredTargetPointingSample>();
    for (int i = 0; i <= CameraFootprintEvaluationApi.MAXIMUM_EVALUATED_SAMPLES; i++)
      oversized.add(
          sample(
              now.plus(new MissionDuration(i * 60_000_000_000L)), satellite, NADIR_TARGET, 0, 90));
    String solutionId = "orbit-" + UUID.randomUUID();
    var initial = orbit(solutionId, craft);
    store.transaction(() -> store.create("orbit", solutionId, initial));
    var pointing =
        pointingResult(solutionId, craft, json.fingerprint(initial), NADIR_TARGET, oversized);
    String pointingId = "pointing-" + UUID.randomUUID();
    store.transaction(() -> store.create("required-target-pointing", pointingId, pointing));
    var area = areaForMeters(10, 10);
    var request =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(pointingId, 1, area));
    var thrown = assertThrows(ApiException.class, () -> post(request, "cap-key"));
    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, thrown.status());
    assertTrue(
        thrown
            .getMessage()
            .contains(String.valueOf(CameraFootprintEvaluationApi.MAXIMUM_EVALUATED_SAMPLES)));
    verifyNoInteractions(http);
  }

  // ---- Persistence, idempotency and outbox ---------------------------------------------------

  @Test
  void idempotentReplayAndConflictOnChangedBody() throws Exception {
    String craft = "cam-eval-idem-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var request =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area));

    var first = post(request, "idem-key-1");
    var replay = post(request, "idem-key-1");
    assertEquals(json.fingerprint(first), json.fingerprint(replay));
    assertEquals(
        1, countRows("SELECT count(*) FROM state_head WHERE kind='camera-footprint-evaluation'"));

    var differentArea = areaForMeters(20, 20);
    var conflicting =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(chain.pointingId(), 1, differentArea));
    assertThrows(ApiException.class, () -> post(conflicting, "idem-key-1"));
  }

  @Test
  void immutableStateAndHistoryAfterLaterUnrelatedActivity() throws Exception {
    String craft = "cam-eval-immutable-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var response =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
            "immutable-key-1");
    var id = response.get("id").asText();
    var originalManifest =
        json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);

    // Later, unrelated activity: a second, independent evaluation.
    String craft2 = "cam-eval-immutable2-" + UUID.randomUUID();
    var cameraModel2 = camera(craft2, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel2 = planning(craft2, "sim-v1", 30);
    var chain2 = seedChain(NADIR_TARGET, samples, cameraModel2, 1, planningModel2, 1);
    post(
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(chain2.pointingId(), 1, area)),
        "immutable-key-2");

    assertEquals(originalManifest, api.manifest(id));
    var historyRow =
        store.version(
            "camera-footprint-evaluation", id, 1, CameraFootprintEvaluationApi.Manifest.class);
    assertTrue(historyRow.isPresent());
    assertEquals(originalManifest, historyRow.get().body());
  }

  // Simulates a newer camera model version becoming available after this evaluation was
  // computed; the persisted manifest (and its stream-backed result) must still reflect exactly
  // the version it pinned, unaffected by anything mission-definition serves afterward for a
  // different version -- the GET paths never re-fetch.
  @Test
  void immutableReplayAfterNewerCameraModelIsPublished() throws Exception {
    String craft = "cam-eval-pin-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModelV1 = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModelV1, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var response =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
            "pin-key-1");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    assertEquals(1, manifest.cameraModelVersion());

    // A hypothetical "newer" camera model version now exists at mission-definition.
    var cameraModelV2 = camera(craft, "sim-v1", 1.5, 2.5, 100, 200, 5, 30, 10, 1);
    when(http.get(
            eq("mission-definition"),
            eq("/internal/simulation-camera-models/" + craft + "/versions/2"),
            eq(JsonNode.class)))
        .thenReturn(envelope(craft, 2, cameraModelV2));

    // Re-reading the already-stored evaluation is a pure store lookup; it still pins version 1.
    var reread = api.manifest(manifest.id());
    assertEquals(1, reread.cameraModelVersion());
    assertEquals(manifest.cameraModelHash(), reread.cameraModelHash());
    var rereadResult = streamedResult(manifest.id());
    assertEquals(1, rereadResult.cameraModelVersion());
  }

  @Test
  void outboxHasExactlyOneEventPerComputationAndNoneOnReplay() throws Exception {
    String craft = "cam-eval-outbox-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var request =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area));

    post(request, "outbox-key-1");
    assertEquals(
        1,
        countRows(
            "SELECT count(*) FROM outbox WHERE event_type='CameraFootprintEvaluationComputed'"));
    post(request, "outbox-key-1");
    assertEquals(
        1,
        countRows(
            "SELECT count(*) FROM outbox WHERE event_type='CameraFootprintEvaluationComputed'"));
  }

  // The rollback trap: a pre-write validation failure proves nothing about rollback because
  // nothing has been written yet. This forces the outbox INSERT itself to fail, strictly after
  // the state/history rows for this computation are written within the same transaction.
  @Test
  void outboxFailureAfterStateWriteRollsBackEverything() throws Exception {
    String craft = "cam-eval-rollback-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var request =
        new CameraFootprintEvaluationApi.Request(
            new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area));

    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_camera_eval_test CHECK (event_type <>"
            + " 'CameraFootprintEvaluationComputed')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> post(request, "rollback-key"));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_camera_eval_test");
    }
    assertEquals(
        0, countRows("SELECT count(*) FROM state_head WHERE kind='camera-footprint-evaluation'"));
    assertEquals(
        0,
        countRows("SELECT count(*) FROM state_history WHERE kind='camera-footprint-evaluation'"));
    assertEquals(
        0,
        countRows(
            "SELECT count(*) FROM outbox WHERE event_type='CameraFootprintEvaluationComputed'"));
    assertEquals(0, countRows("SELECT count(*) FROM idempotency"));

    // The identical request/key can be retried after the persistence fault is removed.
    var retried = post(request, "rollback-key");
    assertEquals(
        json.fingerprint(retried.path("body")),
        json.fingerprint(api.manifest(retried.path("id").asText())));
  }

  // ---- Scope: sampled evidence only, never FEASIBLE ------------------------------------------

  @Test
  void manifestAndStreamedResultReportSampleOnlyScopeAndNeverFeasible() throws Exception {
    String craft = "cam-eval-scope-" + UUID.randomUUID();
    var satellite = new Vector(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + HEIGHT, 0, 0);
    var samples = List.of(sample(now, satellite, NADIR_TARGET, 0, 90));
    var cameraModel = camera(craft, "sim-v1", 1, 2, 100, 200, 5, 30, 10, 1);
    var planningModel = planning(craft, "sim-v1", 30);
    var chain = seedChain(NADIR_TARGET, samples, cameraModel, 1, planningModel, 1);
    var area = areaForMeters(10, 10);
    var response =
        post(
            new CameraFootprintEvaluationApi.Request(
                new CameraFootprintEvaluationQuery(chain.pointingId(), 1, area)),
            "scope-key");
    var manifest = json.convert(response.get("body"), CameraFootprintEvaluationApi.Manifest.class);
    assertEquals(
        CameraFootprintEvaluationScope.SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE, manifest.scope());
    var result = streamedResult(manifest.id());
    assertEquals(cameraModel, result.cameraModel().body());
    assertEquals(planningModel, result.planningModel().body());
    assertEquals(result.cameraModelHash(), json.fingerprint(result.cameraModel()));
    assertEquals(result.planningModelHash(), json.fingerprint(result.planningModel()));
    assertEquals(
        CameraFootprintEvaluationScope.SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE, result.scope());
    assertFalse(json.write(manifest).contains("FEASIBLE"));
    assertFalse(json.write(result).contains("FEASIBLE"));
  }
}
