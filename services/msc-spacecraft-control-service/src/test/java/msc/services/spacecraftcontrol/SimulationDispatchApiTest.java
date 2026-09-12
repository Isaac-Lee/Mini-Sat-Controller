package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import msc.contracts.GroundContracts.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.shared.Ids.*;
import msc.domain.spacecraftcontrol.CommandReleasePolicy;
import msc.domain.tasking.ObservationRequest;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationDispatchApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new CommandCompilerTest().json;
  final MissionInstant now = MissionInstant.tai(1001);
  final UUID scenario = UUID.randomUUID();
  final String id = "prepared-v1";
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator1", "unused");
  JdbcTemplate db;
  StateStore store;
  ServiceHttp http;
  SimulationDispatchApi api;
  CommandCompiler.Prepared prepared;
  CommandAuthorityCheckApi authority;
  CommandScheduleCheckApi schedule;
  AtomicReference<JsonNode> ledger;
  boolean requestCancelled;
  boolean lostAck;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency");
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    http = mock(ServiceHttp.class);
    schedule = mock(CommandScheduleCheckApi.class);
    authority = mock(CommandAuthorityCheckApi.class);
    api = new SimulationDispatchApi(store, json, http, () -> now, schedule, authority);
    prepared =
        new CommandCompiler(json).compile(new CommandLoadId(id), CommandCompilerTest.fixture(true));
    store.transaction(() -> store.create("prepared-command-load", id, prepared));
    var binding = CommandReleasePolicy.Binding.of(prepared.load());
    when(schedule.check(id))
        .thenReturn(
            new CommandScheduleCheckApi.Check(
                binding, now, Optional.of(prepared.sources().schedule()), Set.of()));
    when(authority.check(id))
        .thenReturn(
            new CommandAuthorityCheckApi.Check(
                binding, now, null, null, null, Set.of(), List.of(), Set.of()));
    when(http.get(
            eq("simulator"), eq("/internal/simulation/scenarios/" + scenario), eq(JsonNode.class)))
        .thenReturn(
            json.tree(
                new StateStore.State<>(
                    scenario.toString(),
                    1,
                    Map.of(
                        "environment",
                        "SIMULATION",
                        "mission",
                        prepared.sources().mission(),
                        "correlation",
                        prepared.sources().correlation()))));
    when(http.get(eq("tasking"), anyString(), eq(RequestDetails.class)))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(1);
              String requestId = path.substring(path.lastIndexOf('/') + 1);
              var request =
                  new ObservationRequest(
                      new RequestId(requestId),
                      new AoiId("area"),
                      1,
                      "test",
                      Optional.empty(),
                      1,
                      ObservationRequest.InteractionPreference.AUTO,
                      requestCancelled
                          ? ObservationRequest.Status.CANCELLED
                          : ObservationRequest.Status.SCHEDULED);
              return new RequestDetails(
                  request,
                  "requester",
                  "target",
                  Optional.empty(),
                  new Criteria(1, 1),
                  now,
                  now,
                  "test",
                  List.of());
            });
    when(http.get(eq("planning"), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(1);
              var assignment =
                  prepared.sources().schedule().assignments().stream()
                      .filter(a -> path.contains("/" + a.requestId().value() + "/1"))
                      .findFirst()
                      .orElseThrow();
              return json.tree(
                  Map.of(
                      "body",
                      Map.of(
                          "environment",
                          "SIMULATION",
                          "evaluationModel",
                          "SIMULATION_V1_SAMPLED_REVIEW",
                          "requestRevision",
                          1,
                          "candidateId",
                          assignment.candidateId().value(),
                          "runId",
                          assignment.runId().value(),
                          "schedule",
                          prepared.sources().schedule())));
            });
    when(http.post(eq("anomaly"), anyString(), any(), anyString(), eq(JsonNode.class)))
        .thenReturn(json.tree(Map.of("clear", true, "latch", Map.of("spacecraftId", "sat"))));
    ledger = new AtomicReference<>();
    when(http.get(eq("simulator"), eq(loadPath() + "/" + id), eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              if (ledger.get() != null) return ledger.get();
              throw HttpClientErrorException.create(
                  org.springframework.http.HttpStatus.NOT_FOUND,
                  "absent",
                  org.springframework.http.HttpHeaders.EMPTY,
                  new byte[0],
                  null);
            });
    when(http.post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              JsonNode submission = inv.getArgument(2);
              ledger.set(
                  json.tree(
                      new StateStore.State<>(
                          id,
                          1,
                          Map.of(
                              "load",
                              submission.path("load"),
                              "submissionSha256",
                              json.fingerprint(submission)))));
              if (lostAck) throw new ResourceAccessException("synthetic lost acknowledgment");
              return ledger.get();
            });
  }

  String loadPath() {
    return "/internal/simulation/scenarios/" + scenario + "/loads";
  }

  JsonNode release() {
    return api.release(
        id,
        new SimulationDispatchApi.ReleaseRequest(scenario, prepared.load().checksum(), "review"),
        "release",
        actor);
  }

  @Test
  void releaseIsSeparateFromDeliveryAndAcceptedDeliveryIsIdempotent() {
    var released = release();
    verify(http, never())
        .post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class));
    assertEquals(json.fingerprint(released), json.fingerprint(release()));
    assertEquals("ACCEPTED", api.dispatch(id).body().status());
    requestCancelled = true;
    assertEquals("ACCEPTED", api.dispatch(id).body().status());
    verify(http, times(1))
        .post(eq("simulator"), eq(loadPath()), any(), eq("control-v1:" + id), eq(JsonNode.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='simulation-dispatch-attempt'",
            Integer.class));
  }

  @Test
  void lostAcknowledgmentReconcilesWithoutResendingEvenAfterCancellation() {
    release();
    lostAck = true;
    assertEquals("UNKNOWN", api.dispatch(id).body().status());
    requestCancelled = true;
    var result = api.dispatch(id);
    assertEquals("ACCEPTED", result.body().status());
    assertEquals("RECONCILED", result.body().reason());
    verify(http, times(1))
        .post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class));
  }

  @Test
  void authorityChangeAndCancellationPreventNewDelivery() {
    release();
    when(authority.check(id))
        .thenReturn(
            new CommandAuthorityCheckApi.Check(
                CommandReleasePolicy.Binding.of(prepared.load()),
                now,
                null,
                null,
                null,
                Set.of(),
                List.of(),
                Set.of(CommandReleasePolicy.Reason.AUTHORITY_UNKNOWN)));
    assertThrows(ApiException.class, () -> api.dispatch(id));
    verify(http, never())
        .post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class));
    when(authority.check(id))
        .thenReturn(
            new CommandAuthorityCheckApi.Check(
                CommandReleasePolicy.Binding.of(prepared.load()),
                now,
                null,
                null,
                null,
                Set.of(),
                List.of(),
                Set.of()));
    requestCancelled = true;
    assertThrows(ApiException.class, () -> api.dispatch(id));
  }

  @Test
  void deliveryOutboxRollbackCanRecoverTheAlreadyAcceptedRemoteLoad() {
    release();
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_delivery CHECK (event_type <>"
            + " 'SimulationCommandDeliveryObserved')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class, () -> api.dispatch(id));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_delivery");
    }
    assertTrue(
        store
            .find(SimulationDispatchApi.DELIVERY, id, SimulationDispatchApi.Delivery.class)
            .isEmpty());
    assertEquals("RECONCILED", api.dispatch(id).body().reason());
    verify(http, times(1))
        .post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class));
  }

  void observeExecution() {
    release();
    api.dispatch(id);
    var entries = new ArrayList<Map<String, Object>>();
    var commands = new ArrayList<msc.contracts.SimulationExecutionContracts.CommandEvidence>();
    for (var command : prepared.load().commands()) {
      String hash = json.fingerprint(prepared.sources().catalogsByActivity().get("activity-0"));
      long completion = command.timeTag().ticks() + 100;
      entries.add(
          Map.of(
              "command",
              command,
              "status",
              "EFFECT_APPLIED",
              "completionTick",
              completion,
              "catalogSha256",
              hash));
      commands.add(
          new msc.contracts.SimulationExecutionContracts.CommandEvidence(
              command.id().value(),
              msc.contracts.SimulationExecutionContracts.ModeledOutcome.EFFECT_APPLIED,
              completion,
              hash));
    }
    var updated = (com.fasterxml.jackson.databind.node.ObjectNode) ledger.get().deepCopy();
    updated.put("version", 2);
    ((com.fasterxml.jackson.databind.node.ObjectNode) updated.path("body"))
        .set("entries", json.tree(entries));
    ledger.set(updated);
    var observation =
        new msc.contracts.SimulationExecutionContracts.Observation(
            scenario,
            "sat",
            id,
            "SIMULATION",
            2,
            json.fingerprint(updated),
            commands,
            MissionInstant.tai(1030),
            MissionInstant.tai(1031));
    var evidence =
        new ExecutionEvidenceApi.Evidence(
            observation,
            UUID.randomUUID(),
            MissionInstant.tai(1031),
            MissionInstant.tai(1031),
            json.fingerprint(observation),
            ExecutionEvidenceApi.BindingStatus.UNBOUND_SIMULATION_EVIDENCE);
    store.transaction(
        () -> store.create("simulation-execution-evidence", scenario + ":" + id, evidence));
  }

  @Test
  void receivedEffectsBindToReleasedCommandsAndOriginalRequests() {
    observeExecution();
    var binding = new SimulationExecutionBindingApi(store, json, http);
    var result = binding.reconcile(id, "bind", actor);
    assertEquals("SIMULATION_EFFECTS_CONFIRMED", result.path("body").path("status").asText());
    assertEquals(List.of("activity-0", "activity-1"), binding.read(id).body().requestIds());
    assertEquals(json.fingerprint(result), json.fingerprint(binding.reconcile(id, "bind", actor)));
  }

  @Test
  void mismatchedExecutionLedgerCannotCreateABinding() {
    observeExecution();
    ((com.fasterxml.jackson.databind.node.ObjectNode) ledger.get()).put("version", 3);
    var binding = new SimulationExecutionBindingApi(store, json, http);
    assertThrows(ApiException.class, () -> binding.reconcile(id, "bad", actor));
    assertTrue(
        store
            .find("simulation-bound-execution", id, SimulationExecutionBindingApi.Bound.class)
            .isEmpty());
  }

  @Test
  void cancellingBookedOperationAfterReleasePreventsItsDelivery() {
    var booking =
        new Booking(
            "ground",
            new Reservation(
                "station", 1, "sat", prepared.sources().schedule().key().horizon(), "access", 1),
            BookingStatus.CONFIRMED,
            "simulator",
            "test");
    doAnswer(
            inv -> {
              String path = inv.getArgument(1);
              var assignment =
                  prepared.sources().schedule().assignments().stream()
                      .filter(a -> path.contains(a.activityId().value()))
                      .findFirst()
                      .orElseThrow();
              boolean operation = path.contains("simulation-operations");
              var body = new HashMap<String, Object>();
              body.put("environment", "SIMULATION");
              body.put(
                  "evaluationModel",
                  operation ? "SIMULATION_V1_OPERATION_REVIEW" : "SIMULATION_V1_SAMPLED_REVIEW");
              body.put("requestRevision", 1);
              body.put("runId", assignment.runId().value());
              body.put("sourceRunId", assignment.runId().value());
              body.put("candidateId", operation ? assignment.candidateId().value() : "image-only");
              body.put("activityId", assignment.activityId().value());
              body.put("sourceImageDecision", assignment.requestId().value() + ":1");
              body.put("schedule", prepared.sources().schedule());
              body.put("booking", booking);
              return json.tree(Map.of("body", body));
            })
        .when(http)
        .get(eq("planning"), anyString(), eq(JsonNode.class));
    when(http.get(eq("ground-operations"), eq("/internal/bookings/ground"), eq(Booking.class)))
        .thenReturn(booking);
    release();
    when(http.get(eq("ground-operations"), eq("/internal/bookings/ground"), eq(Booking.class)))
        .thenReturn(
            new Booking(
                booking.id(),
                booking.request(),
                BookingStatus.CANCELLED,
                "simulator",
                "cancelled"));
    assertThrows(ApiException.class, () -> api.dispatch(id));
    verify(http, never())
        .post(eq("simulator"), eq(loadPath()), any(), anyString(), eq(JsonNode.class));
  }
}
