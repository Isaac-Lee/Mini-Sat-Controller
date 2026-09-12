package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.UUID;
import msc.contracts.IlluminationContracts.SpacecraftEclipseResult;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.flightdynamics.Trajectory.Vector;
import msc.domain.time.*;
import msc.orbit.GeneralPerturbationsAdapter;
import msc.orbit.KeplerianOrbitAdapter;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real DB/idempotency behaviour for {@link IlluminationApi#spacecraftEclipse}, calling the
 * controller method directly (like {@code PropellantApiTest}) against a real Testcontainers
 * Postgres, real Flyway-applied schema, and a real pinned Orekit reference archive -- no mocked
 * propagation and no reflection. This exercises actual runtime behaviour end to end: DB
 * persistence, idempotent replay, conflict/missing/ambiguous-identity handling, and the
 * exactly-once event.
 *
 * <p>No Spring context is booted here, so {@code @PreAuthorize} method security is not enforced
 * by this test (there is no AOP proxy without a Spring context). Role parity with the existing
 * access endpoint is asserted structurally, unchanged by this feature, in {@link
 * IlluminationApiTest#postEndpointsRequireTheSameRolesAsTheExistingAccessEndpoint}.
 */
@Testcontainers
class SpacecraftEclipseApiIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  Json json;
  IlluminationApi api;
  OrekitReferenceFrames frames;
  MissionInstant now;
  final Authentication actor = new UsernamePasswordAuthenticationToken("operator1", "unused");

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    new JdbcTemplate(ds).execute("TRUNCATE state_head,state_history,outbox,idempotency");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    now = MissionInstant.tai(2_000_000_000L);
    store =
        new StateStore(
            new JdbcTemplate(ds),
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> now);
    frames =
        new OrekitReferenceFrames(
            Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")),
            System.getenv("MSC_TEST_OREKIT_SHA256"));
    api = new IlluminationApi(store, frames);
  }

  @Test
  void rectangularPredictionPersistsItsScopeAndReplaysWithoutAnotherEvent() {
    var start = frames.fromUtc("2026-09-01T12:00:00");
    var query = new msc.contracts.IlluminationContracts.TargetIlluminationQuery(
        new msc.contracts.IlluminationContracts.Aoi("rectangle", -10, 10, -10, 10, 0),
        new TimeWindow(start, start.plus(new MissionDuration(3600_000_000_000L))), 10);
    var request = new IlluminationApi.TargetIlluminationRequest("norad-63229", query);
    var first = api.rectangularIllumination(request, "rectangle-key", actor);
    String id = first.path("id").asText();
    var result = api.rectangularIlluminationResult(id);
    assertEquals(query, result.query());
    assertEquals(frames.digest(), result.referenceDigest());
    assertEquals(msc.contracts.IlluminationContracts.RectangularIlluminationScope
        .SPATIAL_BOUND_NUMERICAL_EVENT_SEARCH, result.scope());
    assertFalse(result.illuminatedWindows().isEmpty());
    // A fresh controller with no reference archive can replay persisted output without compute.
    var replay = new IlluminationApi(store, null).rectangularIllumination(request, "rectangle-key", actor);
    assertEquals(json.fingerprint(first), json.fingerprint(replay));
    assertEquals(1, store.list("rectangular-illumination", 100).size());
    var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    assertEquals(1, new JdbcTemplate(ds).queryForObject(
        "SELECT count(*) FROM outbox WHERE event_type='RectangularIlluminationPredicted'", Integer.class));
    assertThrows(ApiException.class, () -> api.rectangularIllumination(
        new IlluminationApi.TargetIlluminationRequest("different-craft", query), "rectangle-key", actor));
  }

  @Test
  void intervalCalculationUsesPinnedOwnerAssumptionsAndPersistsReplay() {
    var http = org.mockito.Mockito.mock(ServiceHttp.class);
    var intervalApi = new SolarIntervalApi(store, http, json, frames);
    var start = frames.fromUtc("2026-09-01T12:00:00");
    var horizon = new TimeWindow(start, start.plus(new MissionDuration(10_000_000_000L)));
    var area = new msc.contracts.IlluminationContracts.Aoi("area", -10, 10, -10, 10, 0);
    var assumptions = new msc.contracts.SolarIntervalContracts.Assumptions("sat", "m1", "SIMULATION",
        msc.orbit.OrekitIlluminationPredictor.SOLAR_MODEL, frames.digest(), horizon, area,
        .001, .0001, 5, "explicit integration assumption only");
    org.mockito.Mockito.when(http.get("mission-definition", "/internal/solar-interval-assumptions/sat/versions/2", JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sat", 2, assumptions)));
    var request = new SolarIntervalApi.Request("sat", 2,
        new msc.contracts.IlluminationContracts.TargetIlluminationQuery(area, horizon, 10));
    var first = intervalApi.calculate(request, "interval", actor);
    var saved = intervalApi.read(first.path("id").asText());
    assertEquals(SolarIntervalApi.Outcome.SUPPORTED_BY_DECLARED_ASSUMPTIONS, saved.outcome());
    assertEquals(assumptions, saved.assumptions().body());
    assertEquals(json.fingerprint(saved.assumptions()), saved.assumptionsSha256());
    org.mockito.Mockito.reset(http);
    assertEquals(json.fingerprint(first), json.fingerprint(intervalApi.calculate(request, "interval", actor)));
    org.mockito.Mockito.verifyNoInteractions(http);
    assertEquals(1, store.list("solar-interval", 100).size());
    org.mockito.Mockito.when(http.get("mission-definition", "/internal/solar-interval-assumptions/sat/versions/2", JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sat", 3, assumptions)));
    assertThrows(ApiException.class, () -> intervalApi.calculate(request, "wrong-version", actor));
    assertEquals(1, store.list("solar-interval", 100).size());
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

  private static final double MU = 3.986004418e14;

  private InitialState circularEquatorialOrbit(String solutionId, MissionInstant epoch) {
    return new InitialState(
        solutionId,
        "sim-cart-1",
        epoch,
        new Vector(7_000_000, 0, 0),
        new Vector(0, Math.sqrt(MU / 7_000_000), 0),
        "Synthetic circular geometry fixture");
  }

  private Snapshot gpSnapshot(String id, MeanElements elements) {
    return new Snapshot(
        id,
        elements,
        "test-fixture-provider",
        "https://example.invalid/not-a-real-source",
        now,
        "a".repeat(64),
        "{}");
  }

  private JsonNode post(IlluminationApi.EclipseRequest request, String key) {
    return api.spacecraftEclipse(request, key, actor);
  }

  // G5/G6/G7 regression: the pre-existing Cartesian path still works, persists, replays
  // idempotently without recomputation/second row, rejects a changed body under the same key,
  // and rejects an unknown id.
  @Test
  void cartesianOrbitEclipsePersistsIdempotentlyAndRejectsMismatchedReplay() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-eclipse-cart-1", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(10_800_000_000_000L)));
    var request = new IlluminationApi.EclipseRequest(initial.solutionId(), horizon);

    var first = post(request, "cart-key-1");
    var id = first.get("id").asText();
    var result = json.convert(first.get("body"), SpacecraftEclipseResult.class);
    assertEquals(initial.solutionId(), result.solutionId());
    assertEquals(initial.spacecraftId(), result.spacecraftId());
    assertTrue(result.orbitPropagationModel().isPresent());
    assertEquals(KeplerianOrbitAdapter.MODEL, result.orbitPropagationModel().get());
    assertFalse(result.eclipseWindows().isEmpty());
    assertFalse(result.sunlitWindows().isEmpty());

    // GET returns the persisted record byte-identically (decoded-boundary comparison).
    var fetched = api.spacecraftEclipseResult(id);
    assertEquals(result, fetched);

    // Same key + same body replays without a second computation/row.
    var replay = post(request, "cart-key-1");
    assertEquals(json.fingerprint(first), json.fingerprint(replay));
    assertEquals(1, store.list("spacecraft-eclipse", 100).size());
    assertEquals(
        1,
        countEvents("SpacecraftEclipsePredicted", id),
        "replay must not emit a second event");

    // Same key + different body -> 409.
    var differentHorizon =
        new TimeWindow(epoch, epoch.plus(new MissionDuration(3_600_000_000_000L)));
    var conflictingRequest =
        new IlluminationApi.EclipseRequest(initial.solutionId(), differentHorizon);
    assertThrows(ApiException.class, () -> post(conflictingRequest, "cart-key-1"));

    // Unknown solution id -> 404.
    var unknown =
        new IlluminationApi.EclipseRequest(
            "does-not-exist-" + UUID.randomUUID(), horizon);
    assertThrows(ApiException.class, () -> post(unknown, "unknown-key"));
  }

  // G3/G5: a real public-GP solution produces a persisted result carrying the SGP4/SDP4 model
  // constant, the norad-<id> owner spacecraftId invariant, and a *different*
  // orbitPropagationModel than the Cartesian path above -- proving the API layer's model binding
  // is exercised, not merely present as an unused field.
  @Test
  void publicGpEclipseBindsDistinctModelAndOwnerSpacecraftId() throws Exception {
    var elements = spaceeye(63229);
    var gpId = "gp-63229-" + "a".repeat(64);
    var snapshot = gpSnapshot(gpId, elements);
    store.transaction(() -> store.create("public-orbit", gpId, snapshot));
    var epoch = new GeneralPerturbationsAdapter(frames).epoch(elements);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(10_800_000_000_000L)));
    var request = new IlluminationApi.EclipseRequest(gpId, horizon);

    var response = post(request, "gp-key-1");
    var result = json.convert(response.get("body"), SpacecraftEclipseResult.class);
    assertEquals(gpId, result.solutionId());
    assertEquals("norad-63229", result.spacecraftId());
    assertTrue(result.orbitPropagationModel().isPresent());
    assertEquals(GeneralPerturbationsAdapter.MODEL, result.orbitPropagationModel().get());
    assertNotEquals(KeplerianOrbitAdapter.MODEL, result.orbitPropagationModel().get());

    var id = response.get("id").asText();
    assertEquals(result, api.spacecraftEclipseResult(id));
  }

  // G8: a Cartesian input and a public-GP snapshot sharing the same solution id is ambiguous
  // identity and must fail closed with a conflict, mirroring OrbitApi.input(id).
  @Test
  void sharedIdBetweenCartesianAndGpIsAmbiguous() throws Exception {
    String sharedId = "shared-id-" + UUID.randomUUID();
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit(sharedId, epoch);
    var elements = spaceeye(63229);
    var snapshot = gpSnapshot(sharedId, elements);
    store.transaction(
        () -> {
          store.create("orbit", sharedId, initial);
          return store.create("public-orbit", sharedId, snapshot);
        });
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(3_600_000_000_000L)));
    var request = new IlluminationApi.EclipseRequest(sharedId, horizon);
    var thrown =
        assertThrows(ApiException.class, () -> post(request, "ambiguous-key"));
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, thrown.status());
  }

  @Test
  void gpIdentifierMustMatchNoradAndSnapshotHash() {
    var elements = spaceeye(63229);
    var epoch = new GeneralPerturbationsAdapter(frames).epoch(elements);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(3_600_000_000_000L)));
    for (String id : new String[] {"gp-12345-" + "a".repeat(64), "gp-63229-" + "b".repeat(64)}) {
      store.transaction(() -> store.create("public-orbit", id, gpSnapshot(id, elements)));
      var error = assertThrows(ApiException.class,
          () -> post(new IlluminationApi.EclipseRequest(id, horizon), "bad-binding-" + id));
      assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.status());
    }
  }

  // G7: neither kind present -> 404 (explicit status check, complementing the regression test
  // above).
  @Test
  void unknownIdIsNotFoundWithMissingStatus() {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(3_600_000_000_000L)));
    var request =
        new IlluminationApi.EclipseRequest("no-such-solution-" + UUID.randomUUID(), horizon);
    var thrown = assertThrows(ApiException.class, () -> post(request, "missing-key"));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, thrown.status());
  }

  private int countEvents(String type, String aggregateId) {
    return new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
        .queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type=? AND envelope->>'aggregateId'=?",
            Integer.class,
            type,
            aggregateId);
  }

  // Legacy compatibility: a stored SpacecraftEclipseResult JSON written before
  // orbitPropagationModel existed (no such property at all) must still deserialize, reading back
  // an empty Optional rather than throwing or defaulting to a qualified model.
  @Test
  void legacyJsonWithoutOrbitPropagationModelDeserializesToEmptyOptional() throws Exception {
    var mapper = JsonMapper.builder().findAndAddModules().build();
    String legacyJson =
        """
        {"solutionId":"legacy-1","spacecraftId":"sim-legacy-1",
        "eclipseModel":"orekit-13.1.8-finite-sun-penumbra-inclusive-eclipse-WGS84",
        "solarModelAccuracyNote":"note","referenceDigest":"deadbeef",
        "horizon":{"start":{"seconds":1000,"nanos":0,"scale":"TAI"},
        "end":{"seconds":2000,"nanos":0,"scale":"TAI"}},
        "rootToleranceSeconds":0.001,"maximumCheckSeconds":10.0,
        "sunlitWindows":[],"eclipseWindows":[]}
        """;
    var decoded = mapper.readValue(legacyJson, SpacecraftEclipseResult.class);
    assertEquals("legacy-1", decoded.solutionId());
    assertTrue(decoded.orbitPropagationModel().isEmpty());

    // The preserved ten-argument constructor is still usable directly by any caller/legacy path.
    var viaOldConstructor =
        new SpacecraftEclipseResult(
            "legacy-2",
            "sim-legacy-2",
            "orekit-13.1.8-finite-sun-penumbra-inclusive-eclipse-WGS84",
            "note",
            "deadbeef",
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
            0.001,
            10.0,
            java.util.List.of(),
            java.util.List.of());
    assertTrue(viaOldConstructor.orbitPropagationModel().isEmpty());

    // Explicit null is normalized to empty by the compact constructor, not passed through/thrown.
    var reflected =
        SpacecraftEclipseResult.class
            .getConstructor(
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                TimeWindow.class,
                double.class,
                double.class,
                java.util.List.class,
                java.util.List.class,
                java.util.Optional.class)
            .newInstance(
                "legacy-3",
                "sim-legacy-3",
                "orekit-13.1.8-finite-sun-penumbra-inclusive-eclipse-WGS84",
                "note",
                "deadbeef",
                new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
                0.001,
                10.0,
                java.util.List.of(),
                java.util.List.of(),
                null);
    assertTrue(reflected.orbitPropagationModel().isEmpty());
  }
}
