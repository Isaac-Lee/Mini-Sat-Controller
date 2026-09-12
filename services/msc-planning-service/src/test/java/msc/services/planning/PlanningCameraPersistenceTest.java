package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PlanningCameraPersistenceTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final PlanningCameraApiTest fixtures = new PlanningCameraApiTest();
  final Json json = fixtures.json;
  final ServiceHttp http = fixtures.http;
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");
  StateStore store;
  JdbcTemplate db;
  PlanningCameraApi api;
  PlanningRuns.Published run;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute(
        "TRUNCATE"
            + " state_head,state_history,outbox,inbox,idempotency,planning_work,planning_camera_work");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1000));
    api = new PlanningCameraApi(store, http, json);
    run = fixtures.run();
    store.transaction(() -> store.create("planning-run", run.id(), run));
    store.transaction(
        () -> store.create("planning-input-attempt", fixtures.attempt.id(), fixtures.attempt));
    fixtures.stub(run, fixtures.source(run, 2));
  }

  @Test
  void persistedResponseReplaysWithoutOwnersAndDifferentKeyDoesNotDuplicateEvent() {
    var request = new PlanningCameraApi.Evaluate(1);
    var first = api.evaluate(run.id(), request, "first", actor);
    assertEquals(json.fingerprint(first), json.fingerprint(api.read(run.id(), 1)));
    clearInvocations(http);
    var restarted = new PlanningCameraApi(store, http, json);
    assertEquals(
        json.fingerprint(first),
        json.fingerprint(restarted.evaluate(run.id(), request, "first", actor)));
    verifyNoInteractions(http);
    assertThrows(
        ApiException.class,
        () -> restarted.evaluate(run.id(), new PlanningCameraApi.Evaluate(3), "first", actor));
    verifyNoInteractions(http);
    assertEquals(
        json.fingerprint(first),
        json.fingerprint(restarted.evaluate(run.id(), request, "second", actor)));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='PlanningCameraEvaluated'",
            Integer.class));
    assertEquals(1, store.history("planning-camera", first.path("id").asText()).size());
  }

  @Test
  void outboxFailureRollsBackAssessmentHistoryAndReplayThenRetrySucceeds() {
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_camera CHECK (event_type <>"
            + " 'PlanningCameraEvaluated')");
    var request = new PlanningCameraApi.Evaluate(1);
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> api.evaluate(run.id(), request, "retry", actor));
      assertTrue(store.list("planning-camera", 10).isEmpty());
      assertEquals(
          0,
          db.queryForObject(
              "SELECT count(*) FROM state_history WHERE kind='planning-camera'", Integer.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_camera");
    }
    api.evaluate(run.id(), request, "retry", actor);
    assertEquals(1, store.list("planning-camera", 10).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }
}
