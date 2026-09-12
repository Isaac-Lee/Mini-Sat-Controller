package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.contracts.PointingContracts.RequiredTargetPointingQuery;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.PointingContracts.RequiredTargetPointingScope;
import msc.domain.flightdynamics.AccessPrediction.Target;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.flightdynamics.Trajectory;
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
 * Real DB/idempotency behaviour for {@link RequiredTargetPointingApi}, calling the controller
 * method directly (like {@code SpacecraftEclipseApiIT}) against a real Testcontainers Postgres,
 * real Flyway-applied schema, and a real pinned Orekit reference archive -- no mocked propagation
 * and no reflection. HTTP security is verified separately.
 */
@Testcontainers
class RequiredTargetPointingApiIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  Json json;
  JdbcTemplate db;
  RequiredTargetPointingApi api;
  OrekitReferenceFrames frames;
  MissionInstant now;
  final Authentication actor = new UsernamePasswordAuthenticationToken("operator1", "unused");

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
    frames =
        new OrekitReferenceFrames(
            Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")),
            System.getenv("MSC_TEST_OREKIT_SHA256"));
    api = new RequiredTargetPointingApi(store, frames, json);
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

  private RequiredTargetPointingQuery query(Target target, TimeWindow horizon, int stepSeconds) {
    return new RequiredTargetPointingQuery(target, horizon, stepSeconds, 0);
  }

  private JsonNode post(RequiredTargetPointingApi.Request request, String key) {
    return api.requiredTargetPointing(request, key, actor);
  }

  private int countRows(String sql, Object... args) {
    return db.queryForObject(sql, Integer.class, args);
  }

  // ---- Cartesian path -------------------------------------------------------------------

  @Test
  void cartesianOrbitPointingPersistsIdempotentlyAndRejectsMismatchedReplay() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-pointing-cart-1", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var groundAtEpoch =
        frames.groundPoint(
            new Trajectory.Sample(
                epoch, initial.positionMeters(), initial.velocityMetersPerSecond()));
    var target =
        new Target(
            "under-sat", groundAtEpoch.latitudeDegrees(), groundAtEpoch.longitudeDegrees(), 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(600_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(initial.solutionId(), query(target, horizon, 60));

    var first = post(request, "cart-key-1");
    var id = first.get("id").asText();
    var result = json.convert(first.get("body"), RequiredTargetPointingResult.class);
    assertEquals(initial.solutionId(), result.solutionId());
    assertEquals(initial.spacecraftId(), result.spacecraftId());
    assertEquals(KeplerianOrbitAdapter.MODEL, result.orbitPropagationModel());
    assertEquals(json.fingerprint(initial), result.orbitSourceHash());
    assertEquals(frames.digest(), result.referenceDigest());
    assertEquals(RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE, result.scope());
    assertFalse(result.samples().isEmpty());

    // GET returns the persisted record byte-identically (decoded-boundary comparison).
    assertEquals(result, api.requiredTargetPointingResult(id));

    // Same key + same body replays without a second computation/row/event.
    var replay = post(request, "cart-key-1");
    assertEquals(json.fingerprint(first), json.fingerprint(replay));
    assertEquals(
        1, countRows("SELECT count(*) FROM state_head WHERE kind='required-target-pointing'"));
    assertEquals(
        1,
        countRows(
            "SELECT count(*) FROM outbox WHERE event_type='RequiredTargetPointingComputed' AND"
                + " envelope->>'aggregateId'=?",
            id));

    // Same key + different body -> 409.
    var differentHorizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(300_000_000_000L)));
    var conflicting =
        new RequiredTargetPointingApi.Request(
            initial.solutionId(), query(target, differentHorizon, 60));
    assertThrows(ApiException.class, () -> post(conflicting, "cart-key-1"));

    // Unknown solution id -> 404.
    var unknown =
        new RequiredTargetPointingApi.Request(
            "does-not-exist-" + UUID.randomUUID(), query(target, horizon, 60));
    assertThrows(ApiException.class, () -> post(unknown, "unknown-key"));
  }

  // Proves the fix for the missing Cartesian source-binding check: a store key that does not
  // match the body's own solutionId is a binding invariant violation and must fail closed with
  // 400, mirroring the equivalent GP-side check below.
  @Test
  void cartesianOrbitBodyIdMismatchIsRejected() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var mismatched = circularEquatorialOrbit("actual-solution-id", epoch);
    String storedUnderKey = "different-key-" + UUID.randomUUID();
    store.transaction(() -> store.create("orbit", storedUnderKey, mismatched));
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request = new RequiredTargetPointingApi.Request(storedUnderKey, query(target, horizon, 60));
    var thrown = assertThrows(ApiException.class, () -> post(request, "mismatch-key"));
    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, thrown.status());
  }

  // ---- Public-GP path ---------------------------------------------------------------------

  @Test
  void publicGpPointingBindsDistinctModelOwnerSpacecraftIdAndRawSourceHash() throws Exception {
    var elements = spaceeye(63229);
    var gpId = "gp-63229-" + "a".repeat(64);
    var snapshot = gpSnapshot(gpId, elements);
    store.transaction(() -> store.create("public-orbit", gpId, snapshot));
    var epoch = new GeneralPerturbationsAdapter(frames).epoch(elements);
    // Off-nadir/slant-range/frame correctness for the GP path is proven independently in
    // RequiredTargetPointingCalculatorIT; this API-level test only needs a target that produces a
    // non-empty profile without throwing.
    var target = new Target("gp-target", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(1800_000_000_000L)));
    var request = new RequiredTargetPointingApi.Request(gpId, query(target, horizon, 60));

    var response = post(request, "gp-key-1");
    var result = json.convert(response.get("body"), RequiredTargetPointingResult.class);
    assertEquals(gpId, result.solutionId());
    assertEquals("norad-63229", result.spacecraftId());
    assertEquals(GeneralPerturbationsAdapter.MODEL, result.orbitPropagationModel());
    assertNotEquals(KeplerianOrbitAdapter.MODEL, result.orbitPropagationModel());
    assertEquals(snapshot.rawSha256(), result.orbitSourceHash());

    var id = response.get("id").asText();
    assertEquals(result, api.requiredTargetPointingResult(id));
  }

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
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request = new RequiredTargetPointingApi.Request(sharedId, query(target, horizon, 60));
    var thrown = assertThrows(ApiException.class, () -> post(request, "ambiguous-key"));
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, thrown.status());
  }

  @Test
  void gpIdentifierMustMatchNoradAndSnapshotHash() throws Exception {
    var elements = spaceeye(63229);
    var epoch = new GeneralPerturbationsAdapter(frames).epoch(elements);
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    for (String id : new String[] {"gp-12345-" + "a".repeat(64), "gp-63229-" + "b".repeat(64)}) {
      store.transaction(() -> store.create("public-orbit", id, gpSnapshot(id, elements)));
      var error =
          assertThrows(
              ApiException.class,
              () ->
                  post(
                      new RequiredTargetPointingApi.Request(id, query(target, horizon, 60)),
                      "bad-binding-" + id));
      assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.status());
    }
  }

  @Test
  void unknownIdIsNotFoundWithMissingStatus() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(
            "no-such-solution-" + UUID.randomUUID(), query(target, horizon, 60));
    var thrown = assertThrows(ApiException.class, () -> post(request, "missing-key"));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, thrown.status());
  }

  // ---- Immutability / outbox / idempotency mechanics ---------------------------------------

  @Test
  void stateAndHistoryAreByteIdenticalAfterLaterUnrelatedActivity() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-pointing-immutable-1", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(initial.solutionId(), query(target, horizon, 60));
    var response = post(request, "immutable-key-1");
    var id = response.get("id").asText();
    var originalResult = json.convert(response.get("body"), RequiredTargetPointingResult.class);

    // Later, unrelated activity: a second, independent solution and computation.
    var otherInitial = circularEquatorialOrbit("sim-pointing-immutable-2", epoch);
    store.transaction(() -> store.create("orbit", otherInitial.solutionId(), otherInitial));
    post(
        new RequiredTargetPointingApi.Request(
            otherInitial.solutionId(), query(target, horizon, 60)),
        "immutable-key-2");

    // The first record's head and its version-1 history row are still exactly what was returned.
    assertEquals(originalResult, api.requiredTargetPointingResult(id));
    var historyRow =
        store.version("required-target-pointing", id, 1, RequiredTargetPointingResult.class);
    assertTrue(historyRow.isPresent());
    assertEquals(originalResult, historyRow.get().body());
    assertEquals(1, historyRow.get().version());
  }

  @Test
  void outboxHasExactlyOneEventPerComputationAndNoneOnReplay() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-pointing-outbox-1", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(initial.solutionId(), query(target, horizon, 60));

    post(request, "outbox-key-1");
    assertEquals(
        1,
        countRows("SELECT count(*) FROM outbox WHERE event_type='RequiredTargetPointingComputed'"));

    // Replay: same key, same body -> no new event.
    post(request, "outbox-key-1");
    assertEquals(
        1,
        countRows("SELECT count(*) FROM outbox WHERE event_type='RequiredTargetPointingComputed'"));
  }

  @Test
  void outboxFailureRollsBackApiStateHistoryAndIdempotency() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-pointing-rollback", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(
            initial.solutionId(), query(new Target("t", 0, 0, 0), horizon, 60));
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_pointing_test CHECK (event_type <>"
            + " 'RequiredTargetPointingComputed')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> post(request, "rollback-key"));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_pointing_test");
    }
    assertEquals(
        0, countRows("SELECT count(*) FROM state_head WHERE kind='required-target-pointing'"));
    assertEquals(
        0, countRows("SELECT count(*) FROM state_history WHERE kind='required-target-pointing'"));
    assertEquals(
        0,
        countRows("SELECT count(*) FROM outbox WHERE event_type='RequiredTargetPointingComputed'"));
    assertEquals(0, countRows("SELECT count(*) FROM idempotency"));
    var retry = post(request, "rollback-key");
    assertEquals(
        json.fingerprint(retry.path("body")),
        json.fingerprint(api.requiredTargetPointingResult(retry.path("id").asText())));
  }

  // Two requests racing on the same Idempotency-Key and the same body: store.replay's pre-check
  // runs outside any transaction, so BOTH requests may pass it and BOTH may perform the (wasted,
  // but harmless) Orekit computation -- that is expected and acceptable here, exactly as it is
  // for OrbitApi/IlluminationApi, and this test deliberately does not assert the calculator was
  // invoked only once. What store.idempotent's advisory-lock re-check inside the transaction
  // guarantees, and what this test asserts instead, is the persisted OUTCOME: exactly one
  // persisted state row, one history row and one outbox event, and both callers receiving
  // identical responses (the loser replays the winner's stored response rather than creating a
  // second version). Same technique as PlanningIntakeTest's
  // competingWorkersAndDuplicateEventsHaveOneClaimAndOneQueuedFact. The expensive geometry
  // computation itself stays outside any DB transaction/lock in the production method, exactly as
  // in OrbitApi/IlluminationApi -- this test does not change that, and must not be read as
  // motivation to move it inside one.
  @Test
  void concurrentSameKeyRequestsYieldOnePersistedRecordAndIdenticalResponses() throws Exception {
    var epoch = frames.fromUtc("2026-09-01T00:00:00");
    var initial = circularEquatorialOrbit("sim-pointing-race-1", epoch);
    store.transaction(() -> store.create("orbit", initial.solutionId(), initial));
    var target = new Target("t", 0, 0, 0);
    var horizon = new TimeWindow(epoch, epoch.plus(new MissionDuration(60_000_000_000L)));
    var request =
        new RequiredTargetPointingApi.Request(initial.solutionId(), query(target, horizon, 60));
    String key = "race-key-1";

    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return post(request, key);
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return post(request, key);
              });
      start.countDown();
      var resultA = a.get(30, java.util.concurrent.TimeUnit.SECONDS);
      var resultB = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(json.fingerprint(resultA), json.fingerprint(resultB));
    }
    assertEquals(
        1, countRows("SELECT count(*) FROM state_head WHERE kind='required-target-pointing'"));
    assertEquals(
        1, countRows("SELECT count(*) FROM state_history WHERE kind='required-target-pointing'"));
    assertEquals(
        1,
        countRows("SELECT count(*) FROM outbox WHERE event_type='RequiredTargetPointingComputed'"));
    assertEquals(
        1,
        countRows(
            "SELECT count(*) FROM idempotency WHERE scope=? AND request_key=?",
            "required-target-pointing:" + actor.getName(),
            key));
  }
}
