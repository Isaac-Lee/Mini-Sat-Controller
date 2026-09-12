package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PlanningIntakeTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  PlanningIntake intake;
  JdbcTemplate db;
  Json json;
  MissionInstant now = new MissionInstant(1000, 0, TimeScale.TAI);

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute(
        "TRUNCATE"
            + " state_head,state_history,outbox,inbox,planning_work,planning_illumination_work,planning_camera_work");
    db.update("UPDATE planning_inputs_epoch SET epoch=0");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    intake = new PlanningIntake(db, store, json);
  }

  AcceptedRequest request(String id, long revision, int priority) {
    return new AcceptedRequest(
        id,
        revision,
        new Area("area", 127, 36, 127.1, 36.1, "test"),
        new Criteria(1, 1),
        Optional.empty(),
        priority);
  }

  ServiceEvent event(String type, String id, long version, Object payload) {
    return new ServiceEvent(
        UUID.randomUUID(), 1, type, id, version, UUID.randomUUID(), null, now, json.tree(payload));
  }

  void accept(AcceptedRequest r) {
    store.transaction(
        () -> {
          intake.accepted(r, event("ObservationRequestAccepted", r.requestId(), r.revision(), r));
          return null;
        });
  }

  void invalidate(String id, long revision) {
    store.transaction(
        () -> {
          var p = new RequestProgress(id, revision, "test cancellation");
          intake.invalidate(p, event("ObservationRequestInvalidated", id, revision + 1, p));
          return null;
        });
  }

  PlanningInputs.Attempt attempt(PlanningIntake.Claim c) {
    return new PlanningInputs.Attempt(
        UUID.randomUUID().toString(),
        c.requestId(),
        c.revision(),
        now,
        "WAITING_INPUTS",
        Optional.empty(),
        List.of(),
        List.of("EXPLICIT_TEST_MISSING_INPUT"));
  }

  @Test
  void runIndexAndCandidatesPublishOnlyWithTheLiveClaim() {
    var fixtures = new PlanningRunsTest();
    String id = UUID.randomUUID().toString();
    accept(request(id, 1, 10));
    var stale = intake.claim().orElseThrow();
    var first = fixtures.attempt(id, 1, fixtures.asset());
    invalidate(id, 1);
    intake.finish(stale, first);
    assertTrue(store.find("planning-run-index", first.id(), PlanningRuns.Index.class).isEmpty());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='planning-resource-assessment'",
            Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='planning-run'", Integer.class));
    accept(request(id, 2, 10));
    var current = intake.claim().orElseThrow();
    var next = fixtures.attempt(id, 2, fixtures.asset());
    intake.finish(current, next);
    var index = store.require("planning-run-index", next.id(), PlanningRuns.Index.class).body();
    assertEquals(1, index.runIds().size());
    var run =
        store
            .require("planning-run", index.runIds().getFirst(), PlanningRuns.Published.class)
            .body();
    assertEquals("QUEUED", intake.illuminationStatus(run.id()).get("status"));
    assertEquals("QUEUED", intake.cameraStatus(run.id()).get("status"));
    assertEquals(2, run.requestRevision());
    assertEquals(next.id(), run.inputAttemptId());
    var assessment =
        store
            .require("planning-resource-assessment", run.id(), PlanningResources.Assessment.class)
            .body();
    assertEquals(next.id(), assessment.inputAttemptId());
    assertEquals(run.id(), assessment.runId());
    assertEquals("SCHEDULE_HEADS_UNREAD", assessment.scheduleContextStatus());
    assertTrue(assessment.candidates().getFirst().forecast().isEmpty());
    assertTrue(
        assessment
            .candidates()
            .getFirst()
            .issues()
            .contains("RESOURCE_INPUT_INVALID_OR_OUTSIDE_VALIDITY"));
    assertEquals(
        json.fingerprint(next),
        json.fingerprint(
            store
                .require("planning-input-attempt", next.id(), PlanningInputs.Attempt.class)
                .body()));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='PlanningRunRecorded'", Integer.class));
    intake.finish(current, next);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='planning-run'", Integer.class));
  }

  @Test
  void realResourceForecastPersistsWithRunAndSurvivesLaterCancellation() {
    var fixtures = new PlanningResourcesTest();
    String id = UUID.randomUUID().toString();
    accept(request(id, 1, 10));
    var claim = intake.claim().orElseThrow();
    var captured = fixtures.runs.attempt(id, 1, fixtures.asset(50, 0));
    intake.finish(claim, captured);
    var index = store.require("planning-run-index", captured.id(), PlanningRuns.Index.class).body();
    String runId = index.runIds().getFirst();
    var assessment =
        store
            .require("planning-resource-assessment", runId, PlanningResources.Assessment.class)
            .body();
    assertEquals(
        msc.domain.planning.ResourceValidation.Status.VALIDATED,
        assessment.candidates().getFirst().forecast().orElseThrow().status());
    invalidate(id, 1);
    assertEquals(
        assessment,
        store
            .require("planning-resource-assessment", runId, PlanningResources.Assessment.class)
            .body());
  }

  @Test
  void cancelledClaimCannotPublishGeometryCacheButNextRevisionCan() {
    String id = UUID.randomUUID().toString();
    accept(request(id, 1, 10));
    var stale = intake.claim().orElseThrow();
    var value =
        json.tree(Map.of("searchKey", "source-bound-search", "scope", "POINT_GEOMETRY_ONLY"));
    var evidence =
        new PlanningInputs.Evidence(
            "flight-dynamics", "/internal/access-predictions", json.fingerprint(value), value);
    var asset =
        new PlanningInputs.Asset(
            "sim-craft", Map.of(), List.of("SENSOR"), Optional.empty(), Optional.of(evidence));
    var first =
        new PlanningInputs.Attempt(
            UUID.randomUUID().toString(),
            id,
            1,
            now,
            "WAITING_INPUTS",
            Optional.empty(),
            List.of(asset),
            List.of());
    invalidate(id, 1);
    intake.finish(stale, first);
    assertTrue(
        store
            .find("planning-geometry", "source-bound-search", PlanningInputs.Evidence.class)
            .isEmpty());
    assertTrue(
        store.find("planning-input-attempt", first.id(), PlanningInputs.Attempt.class).isEmpty());
    accept(request(id, 2, 10));
    var current = intake.claim().orElseThrow();
    assertTrue(current.attemptNumber() > stale.attemptNumber());
    var next =
        new PlanningInputs.Attempt(
            UUID.randomUUID().toString(),
            id,
            2,
            now,
            "WAITING_INPUTS",
            Optional.empty(),
            List.of(asset),
            List.of());
    intake.finish(current, next);
    assertEquals(
        evidence,
        store
            .require("planning-geometry", "source-bound-search", PlanningInputs.Evidence.class)
            .body());
    assertEquals(
        next,
        store.require("planning-input-attempt", next.id(), PlanningInputs.Attempt.class).body());
  }

  @Test
  void invalidationBeforeAcceptanceCreatesATombstoneAndNewRevisionStillWorks() {
    String id = UUID.randomUUID().toString();
    invalidate(id, 1);
    accept(request(id, 1, 10));
    assertTrue(intake.claim().isEmpty());
    accept(request(id, 2, 20));
    var claim = intake.claim().orElseThrow();
    assertEquals(2, claim.revision());
    invalidate(id, 1);
    intake.finish(claim, attempt(claim));
    assertEquals("WAITING_INPUTS", intake.status(id).get("status"));
  }

  @Test
  void cancellationAndRevisionChangesFenceAlreadyRunningWork() {
    String id = UUID.randomUUID().toString();
    accept(request(id, 1, 10));
    var old = intake.claim().orElseThrow();
    invalidate(id, 1);
    intake.finish(old, attempt(old));
    assertTrue(store.list("planning-input-attempt", 100).isEmpty());
    accept(request(id, 2, 10));
    var second = intake.claim().orElseThrow();
    accept(request(id, 3, 10));
    intake.finish(second, attempt(second));
    assertTrue(store.list("planning-input-attempt", 100).isEmpty());
    assertEquals("QUEUED", intake.status(id).get("status"));
    assertEquals(3, intake.claim().orElseThrow().revision());
  }

  @Test
  void competingWorkersAndDuplicateEventsHaveOneClaimAndOneQueuedFact() throws Exception {
    String id = UUID.randomUUID().toString();
    var request = request(id, 1, 10);
    accept(request);
    accept(request);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> intake.claim());
      var b = pool.submit(() -> intake.claim());
      assertNotEquals(a.get().isPresent(), b.get().isPresent());
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='PlanningRequestQueued'", Integer.class));
    assertThrows(ApiException.class, () -> accept(request(id, 1, 90)));
  }

  @Test
  void priorityInputWakeupsAndExpiredLeasesAreDurable() {
    String low = UUID.randomUUID().toString(), high = UUID.randomUUID().toString();
    accept(request(low, 1, 1));
    accept(request(high, 1, 99));
    var first = intake.claim().orElseThrow();
    assertEquals(high, first.requestId());
    intake.finish(first, attempt(first));
    var second = intake.claim().orElseThrow();
    assertEquals(low, second.requestId());
    intake.finish(second, attempt(second));
    assertTrue(intake.claim().isEmpty());
    store.transaction(
        () -> {
          intake.changedInputs();
          return null;
        });
    db.update("UPDATE planning_work SET last_started_at=now()-interval '6 seconds'");
    var retry = intake.claim().orElseThrow();
    assertEquals(high, retry.requestId());
    db.update(
        "UPDATE planning_work SET lease_until=now()-interval '1 second' WHERE request_id=?", high);
    intake.finish(retry, attempt(retry));
    assertEquals(2, store.list("planning-input-attempt", 100).size());
    var replacement = intake.claim().orElseThrow();
    assertNotEquals(retry.token(), replacement.token());
  }

  @Test
  void inputChangeDuringCollectionIsNotLostAndReceiptSharesInboxTransaction() {
    String id = UUID.randomUUID().toString();
    accept(request(id, 1, 10));
    var claim = intake.claim().orElseThrow();
    store.transaction(
        () -> {
          intake.changedInputs();
          return null;
        });
    intake.finish(claim, attempt(claim));
    db.update("UPDATE planning_work SET last_started_at=now()-interval '6 seconds'");
    assertTrue(intake.claim().isPresent());
    String rolledBack = UUID.randomUUID().toString();
    UUID inbox = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  store.receive(inbox);
                  intake.accepted(
                      request(rolledBack, 1, 10),
                      event(
                          "ObservationRequestAccepted", rolledBack, 1, request(rolledBack, 1, 10)));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    assertThrows(ApiException.class, () -> intake.status(rolledBack));
  }
}
