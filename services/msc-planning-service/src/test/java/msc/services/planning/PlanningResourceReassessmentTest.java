package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PlanningResourceReassessmentTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final PlanningResourcesTest fixture = new PlanningResourcesTest();
  final Json json = fixture.f.json;
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator1", "unused");
  StateStore store;
  JdbcTemplate db;
  PlanningResourceReassessmentApi api;
  PlanningRuns.Published run;
  MissionInstant now;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency");
    now = MissionInstant.tai(1000);
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    api = new PlanningResourceReassessmentApi(store, json, () -> now, db);
    var source = fixture.asset(80, 0);
    var inputs =
        new java.util.EnumMap<
            msc.domain.planning.PlanningDataSnapshot.Input, PlanningInputs.Evidence>(
            msc.domain.planning.PlanningDataSnapshot.Input.class);
    source.inputs().forEach((input, evidence) -> inputs.put(input, wireEvidence(evidence)));
    var asset =
        new PlanningInputs.Asset(
            source.spacecraftId(),
            inputs,
            source.missing(),
            source.catalog().map(this::wireEvidence),
            source.pointGeometry().map(this::wireEvidence),
            source.simulationModel().map(this::wireEvidence),
            source.activityOptions(),
            source.operations());
    var attempt = fixture.runs.attempt(UUID.randomUUID().toString(), 1, asset);
    run = new PlanningRuns(store, json).derive(attempt, asset);
    store.transaction(
        () -> {
          store.create("planning-input-attempt", attempt.id(), attempt);
          return store.create("planning-run", run.id(), run);
        });
  }

  private PlanningInputs.Evidence wireEvidence(PlanningInputs.Evidence source) {
    // Real Planning capture hashes parsed owner HTTP JSON, not in-memory DecimalNode formatting.
    var value =
        json.read(json.write(source.value()), com.fasterxml.jackson.databind.JsonNode.class);
    return new PlanningInputs.Evidence(
        source.service(), source.path(), json.fingerprint(value), value);
  }

  @Test
  void newScheduleChangesNewAssessmentButReplayAndOldEvidenceStayImmutable() {
    var first = api.reassess(run.id(), "first", actor);
    var old = json.convert(first.path("body"), PlanningResourceReassessmentApi.Reassessment.class);
    assertTrue(old.resources().scheduleHeads().isEmpty());
    assertTrue(old.resources().candidates().getFirst().issues().isEmpty());
    assertTrue(old.resources().candidates().getFirst().forecast().isPresent());
    var scheduled = fixture.existing("later-commit", 1100, 1110);
    store.transaction(
        () -> {
          store.create("planning-activity-catalog", "later-commit", fixture.f.catalog());
          return store.create(
              "mission-schedule", json.fingerprint(scheduled.key()), scheduled.snapshot());
        });
    var second = api.reassess(run.id(), "second", actor);
    var current =
        json.convert(second.path("body"), PlanningResourceReassessmentApi.Reassessment.class);
    assertEquals(1, current.resources().scheduleHeads().size());
    assertTrue(current.resources().candidates().getFirst().issues().isEmpty());
    assertEquals(
        1,
        old.resources()
            .candidates()
            .getFirst()
            .forecast()
            .orElseThrow()
            .trajectory()
            .getLast()
            .storedMb(),
        1e-9);
    assertEquals(
        2,
        current
            .resources()
            .candidates()
            .getFirst()
            .forecast()
            .orElseThrow()
            .trajectory()
            .getLast()
            .storedMb(),
        1e-9);
    assertEquals(json.fingerprint(first), json.fingerprint(api.reassess(run.id(), "first", actor)));
    assertEquals(old, api.result(first.path("id").asText()).body());
    assertEquals(
        json.fingerprint(run),
        json.fingerprint(
            store.require("planning-run", run.id(), PlanningRuns.Published.class).body()));
  }

  @Test
  void agedTelemetryAndPastCandidateAreEvaluatedAtCurrentClock() {
    api.reassess(run.id(), "fresh", actor);
    now = MissionInstant.tai(1400);
    var saved = api.reassess(run.id(), "aged", actor);
    var result =
        json.convert(saved.path("body"), PlanningResourceReassessmentApi.Reassessment.class);
    assertEquals(now, result.evaluatedAt());
    var candidate = result.resources().candidates().getFirst();
    assertTrue(candidate.issues().contains("FRESH_RESOURCE_INITIAL_STATE_REQUIRED"));
    assertTrue(candidate.issues().contains("CANDIDATE_START_PRECEDES_EVALUATION"));
    assertTrue(candidate.forecast().isEmpty());
  }

  @Test
  void eventFailureRollsBackResultAndAllowsSameKeyRetry() {
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_reassessment_test CHECK (event_type <>"
            + " 'PlanningResourcesReassessed')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> api.reassess(run.id(), "rollback", actor));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_reassessment_test");
    }
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='planning-resource-reassessment'",
            Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_history WHERE kind='planning-resource-reassessment'",
            Integer.class));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    assertNotNull(api.reassess(run.id(), "rollback", actor).get("body"));
  }

  @Test
  void mismatchedStoredRunIsRejectedBeforeAnyResultIsWritten() {
    var altered =
        new PlanningRuns.Published(
            run.id(),
            "different-request",
            run.requestRevision(),
            run.inputAttemptId(),
            run.spacecraftId(),
            run.sourceHashes(),
            run.catalog(),
            run.simulationModel(),
            run.geometry(),
            run.run(),
            run.operations());
    store.transaction(() -> store.update("planning-run", run.id(), 1, altered));
    assertThrows(ApiException.class, () -> api.reassess(run.id(), "tampered", actor));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='planning-resource-reassessment'",
            Integer.class));
  }
}
