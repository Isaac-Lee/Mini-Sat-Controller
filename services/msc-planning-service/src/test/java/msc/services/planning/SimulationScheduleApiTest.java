package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.UUID;
import msc.contracts.TaskingContracts.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.ObservationRequest;
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
class SimulationScheduleApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final PlanningResourcesTest fixture = new PlanningResourcesTest();
  final Json json = fixture.f.json;
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator1", "unused");
  StateStore store;
  JdbcTemplate db;
  SimulationScheduleApi api;
  ServiceHttp http;
  PlanningRuns.Published run;
  MissionInstant now;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency,planning_work");
    now = MissionInstant.tai(1000);
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    http = mock(ServiceHttp.class);
    api = new SimulationScheduleApi(store, json, http, () -> now, db);
    var source = fixture.asset(80, 0);
    var inputs =
        new java.util.EnumMap<
            msc.domain.planning.PlanningDataSnapshot.Input, PlanningInputs.Evidence>(
            msc.domain.planning.PlanningDataSnapshot.Input.class);
    source.inputs().forEach((input, evidence) -> inputs.put(input, wireEvidence(evidence)));
    var rawOperations = new PlanningOperationsTest().captured();
    var catalogMap =
        new java.util.EnumMap<
            msc.contracts.MissionCatalogBindingContracts.Role, PlanningInputs.Evidence>(
            msc.contracts.MissionCatalogBindingContracts.Role.class);
    rawOperations
        .catalogs()
        .forEach((role, evidence) -> catalogMap.put(role, wireEvidence(evidence)));
    var operations =
        new PlanningOperations.Captured(
            wireEvidence(rawOperations.bindings()),
            wireEvidence(rawOperations.resourceProfiles()),
            catalogMap);
    var asset =
        new PlanningInputs.Asset(
            source.spacecraftId(),
            inputs,
            source.missing(),
            source.catalog().map(this::wireEvidence),
            source.pointGeometry().map(this::wireEvidence),
            source.simulationModel().map(this::wireEvidence),
            source.activityOptions(),
            Optional.of(operations));
    var attempt = fixture.runs.attempt(UUID.randomUUID().toString(), 1, asset);
    run = new PlanningRuns(store, json).derive(attempt, asset);
    store.transaction(
        () -> {
          store.create("planning-input-attempt", attempt.id(), attempt);
          store.create("planning-run", run.id(), run);
          db.update(
              "INSERT INTO planning_work(request_id,revision,last_attempt_id,status)"
                  + " VALUES(?,1,?,'WAITING_INPUTS')",
              run.requestId(),
              attempt.id());
          return store.create(
              "planning-camera",
              json.fingerprint(List.of(run.id(), 1L)),
              new PlanningCameraApi.Assessment(
                  run.id(),
                  json.fingerprint(run),
                  json.tree(Map.of("version", 1)),
                  List.of(
                      new PlanningCameraApi.CandidateResult(
                          run.run().candidates().getFirst().id().value(),
                          "pointing",
                          json.tree(Map.of("body", Map.of("computedSampleCount", 2))))),
                  "SAMPLED"));
        });
  }

  private RequestDetails live(ObservationRequest.Status status) {
    var request =
        new ObservationRequest(
            new RequestId(run.requestId()),
            new AoiId("area"),
            1,
            "simulation",
            Optional.empty(),
            1,
            ObservationRequest.InteractionPreference.AUTO,
            status);
    return new RequestDetails(
        request,
        "user",
        "target",
        Optional.empty(),
        new Criteria(1, 1),
        now,
        now,
        "test",
        List.of());
  }

  private SimulationScheduleApi.Commit command(long expected) {
    return new SimulationScheduleApi.Commit(
        run.run().candidates().getFirst().id().value(), 1, expected, "operator-v1-review");
  }

  private com.fasterxml.jackson.databind.JsonNode commit(String key) {
    when(http.get(eq("tasking"), anyString(), eq(RequestDetails.class)))
        .thenReturn(live(ObservationRequest.Status.ACCEPTED));
    return api.commit(run.id(), command(0), key, actor);
  }

  private PlanningInputs.Evidence wireEvidence(PlanningInputs.Evidence source) {
    // Real Planning capture hashes parsed owner HTTP JSON, not in-memory DecimalNode formatting.
    var value =
        json.read(json.write(source.value()), com.fasterxml.jackson.databind.JsonNode.class);
    return new PlanningInputs.Evidence(
        source.service(), source.path(), json.fingerprint(value), value);
  }

  @Test
  void commitsOwnedSimulationScheduleAndReplaysWithoutRecheckingMutableInputs() {
    db.update(
        "UPDATE planning_work SET lease_token=?,lease_until=now()+interval '60 seconds'",
        UUID.randomUUID());
    String original = json.fingerprint(run);
    var result = commit("commit");
    var saved = json.convert(result.path("body"), SimulationScheduleApi.Committed.class);
    assertEquals("SIMULATION", saved.environment());
    assertEquals(SimulationScheduleApi.MODEL, saved.evaluationModel());
    assertEquals(1, saved.schedule().version());
    assertEquals(run.requestId(), saved.schedule().assignments().getFirst().requestId().value());
    assertEquals(
        run.run().candidates().getFirst().id(),
        saved.schedule().assignments().getFirst().candidateId());
    assertTrue(saved.deferredChecks().contains("PRECISION_ATTITUDE"));
    assertEquals(
        original,
        json.fingerprint(
            store.require("planning-run", run.id(), PlanningRuns.Published.class).body()));
    assertEquals(2, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    assertEquals(
        "SIMULATION_COMMITTED",
        db.queryForObject("SELECT status FROM planning_work", String.class));
    assertNull(db.queryForObject("SELECT lease_token FROM planning_work", UUID.class));
    when(http.get(eq("tasking"), anyString(), eq(RequestDetails.class)))
        .thenReturn(live(ObservationRequest.Status.CANCELLED));
    assertEquals(
        json.fingerprint(result),
        json.fingerprint(api.commit(run.id(), command(0), "commit", actor)));
    assertEquals(saved, api.read(run.requestId(), 1).body());
  }

  @Test
  void outboxFailureRollsBackScheduleCatalogAndRequestDecisionTogether() {
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_simulation_commit CHECK (event_type <>"
            + " 'ScheduleVersionCommitted')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class, () -> commit("retry"));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_simulation_commit");
    }
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind IN"
                + " ('mission-schedule','simulation-v1-schedule','planning-activity-catalog')",
            Integer.class));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    assertEquals(
        "WAITING_INPUTS", db.queryForObject("SELECT status FROM planning_work", String.class));
    assertNotNull(commit("retry").get("body"));
  }

  @Test
  void supersededAttemptAndStaleResourcesCannotCommit() {
    db.update("UPDATE planning_work SET last_attempt_id='newer-attempt'");
    assertThrows(ApiException.class, () -> commit("superseded"));
    db.update("UPDATE planning_work SET last_attempt_id=?", run.inputAttemptId());
    now = MissionInstant.tai(1400);
    assertThrows(ApiException.class, () -> commit("stale"));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='mission-schedule'", Integer.class));
  }

  @Test
  void changedVersionOrSecondDecisionCannotCommit() {
    when(http.get(eq("tasking"), anyString(), eq(RequestDetails.class)))
        .thenReturn(live(ObservationRequest.Status.ACCEPTED));
    assertThrows(
        ApiException.class, () -> api.commit(run.id(), command(1), "wrong-version", actor));
    commit("first");
    assertThrows(ApiException.class, () -> commit("duplicate"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='mission-schedule'", Integer.class));
  }

  msc.contracts.GroundContracts.Booking booking(
      msc.contracts.GroundContracts.BookingStatus status) {
    return new msc.contracts.GroundContracts.Booking(
        "booking",
        new msc.contracts.GroundContracts.Reservation(
            "station",
            1,
            run.spacecraftId(),
            new msc.domain.time.TimeWindow(MissionInstant.tai(1110), MissionInstant.tai(1140)),
            "access",
            1),
        status,
        "simulator",
        "test");
  }

  SimulationDownlinkScheduleApi.Commit downlinkCommand() {
    return new SimulationDownlinkScheduleApi.Commit(
        "booking",
        new msc.domain.time.TimeWindow(MissionInstant.tai(1120), MissionInstant.tai(1130)),
        0,
        "downlink review");
  }

  @Test
  void downlinkKeepsImagingHistoryAndRecomputesCombinedReservoirs() {
    commit("image");
    when(http.get(
            eq("ground-operations"), anyString(), eq(msc.contracts.GroundContracts.Booking.class)))
        .thenReturn(booking(msc.contracts.GroundContracts.BookingStatus.CONFIRMED));
    var downlink = new SimulationDownlinkScheduleApi(store, json, http, () -> now, db);
    var result = downlink.commit(run.id(), downlinkCommand(), "downlink", actor);
    var saved = json.convert(result.path("body"), SimulationDownlinkScheduleApi.Decision.class);
    assertEquals("SIMULATION_V1_OPERATION_REVIEW", saved.evaluationModel());
    assertNotEquals(run.id(), saved.runId());
    assertEquals(run.id(), saved.sourceRunId());
    assertEquals(saved.proposal().id(), saved.schedule().assignments().getFirst().runId());
    var definition =
        json.convert(saved.catalog().value(), msc.contracts.CatalogContracts.CatalogEntry.class)
            .activity();
    assertDoesNotThrow(
        () ->
            msc.domain.planning.PlanningRun.restore(saved.proposal(), (id, version) -> definition));
    assertEquals(run.requestId() + ":1", saved.sourceImageDecision());
    assertEquals(
        "booking",
        saved.schedule().resourceValidation().externalReservations().getFirst().bookingReference());
    assertEquals(
        0, saved.resources().forecast().orElseThrow().trajectory().getLast().storedMb(), 1e-9);
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='mission-schedule'", Integer.class));
    assertEquals(
        json.fingerprint(result),
        json.fingerprint(downlink.commit(run.id(), downlinkCommand(), "downlink", actor)));
    assertEquals(
        json.fingerprint(run),
        json.fingerprint(
            store.require("planning-run", run.id(), PlanningRuns.Published.class).body()));
  }

  @Test
  void unconfirmedBookingAndFailedEventCannotLeaveDownlinkSchedule() {
    commit("image");
    var downlink = new SimulationDownlinkScheduleApi(store, json, http, () -> now, db);
    when(http.get(
            eq("ground-operations"), anyString(), eq(msc.contracts.GroundContracts.Booking.class)))
        .thenReturn(booking(msc.contracts.GroundContracts.BookingStatus.CANCELLED));
    assertThrows(
        ApiException.class, () -> downlink.commit(run.id(), downlinkCommand(), "downlink", actor));
    when(http.get(
            eq("ground-operations"), anyString(), eq(msc.contracts.GroundContracts.Booking.class)))
        .thenReturn(booking(msc.contracts.GroundContracts.BookingStatus.CONFIRMED));
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_downlink CHECK (event_type <>"
            + " 'ScheduleVersionCommitted') NOT VALID");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> downlink.commit(run.id(), downlinkCommand(), "downlink", actor));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_downlink");
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='mission-schedule'", Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='simulation-v1-operation'", Integer.class));
    assertNotNull(downlink.commit(run.id(), downlinkCommand(), "downlink", actor).path("body"));
  }
}
