package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
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
class PlanningIlluminationPersistenceTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final PlanningIlluminationApiTest fixtures = new PlanningIlluminationApiTest();
  final Json json = fixtures.json;
  final ServiceHttp http = mock(ServiceHttp.class);
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");
  StateStore store;
  JdbcTemplate db;
  PlanningIlluminationApi api;
  PlanningRuns.Published run;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,inbox,idempotency,planning_work");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1000));
    api = new PlanningIlluminationApi(store, http, json);
    run = fixtures.run();
    store.transaction(() -> store.create("planning-run", run.id(), run));
    var source = fixtures.source(run, 2);
    when(http.get(eq("mission-definition"), anyString(), eq(JsonNode.class))).thenReturn(source);
    when(http.post(eq("flight-dynamics"), anyString(), any(), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            call ->
                json.tree(
                    Map.of(
                        "id",
                        "fd-result",
                        "version",
                        1,
                        "body",
                        Map.of(
                            "request",
                            json.tree(call.getArgument(2)),
                            "assumptions",
                            source,
                            "assumptionsSha256",
                            json.fingerprint(source),
                            "outcome",
                            "SUPPORTED_BY_DECLARED_ASSUMPTIONS"))));
  }

  @Test
  void persistedResponseReplaysWithoutOwnersAndDifferentKeyDoesNotDuplicateEvent() {
    var request = new PlanningIlluminationApi.Evaluate(2);
    var first = api.evaluate(run.id(), request, "first", actor);
    assertEquals(json.fingerprint(first), json.fingerprint(api.read(run.id(), 2)));
    clearInvocations(http);
    var restarted = new PlanningIlluminationApi(store, http, json);
    assertEquals(
        json.fingerprint(first),
        json.fingerprint(restarted.evaluate(run.id(), request, "first", actor)));
    verifyNoInteractions(http);
    assertThrows(
        ApiException.class,
        () ->
            restarted.evaluate(run.id(), new PlanningIlluminationApi.Evaluate(3), "first", actor));
    verifyNoInteractions(http);
    assertEquals(
        json.fingerprint(first),
        json.fingerprint(restarted.evaluate(run.id(), request, "second", actor)));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='PlanningIlluminationEvaluated'",
            Integer.class));
    assertEquals(1, store.history("planning-illumination", first.path("id").asText()).size());
  }

  @Test
  void outboxFailureRollsBackAssessmentHistoryAndReplayThenRetrySucceeds() {
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_illumination CHECK (event_type <>"
            + " 'PlanningIlluminationEvaluated')");
    var request = new PlanningIlluminationApi.Evaluate(2);
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> api.evaluate(run.id(), request, "retry", actor));
      assertTrue(store.list("planning-illumination", 10).isEmpty());
      assertEquals(
          0,
          db.queryForObject(
              "SELECT count(*) FROM state_history WHERE kind='planning-illumination'",
              Integer.class));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_illumination");
    }
    api.evaluate(run.id(), request, "retry", actor);
    assertEquals(1, store.list("planning-illumination", 10).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }
}
