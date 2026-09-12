package msc.services.spacecraftcontrol;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import msc.contracts.SimulationExecutionContracts.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionEvidenceApiIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Container
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management-alpine");

  static final String PASSWORD = "synthetic-test-password-only";

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.rabbitmq.host", rabbit::getHost);
    r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
    r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    r.add("msc.security.mode", () -> "local");
    for (var role : List.of("admin", "operator", "requester", "service"))
      r.add("msc.security.local." + role + "-password", () -> PASSWORD);
    r.add("msc.time.source", () -> "synthetic-test-offset");
    r.add("msc.time.utc-tai-offset-seconds", () -> 37);
    r.add("msc.time.valid-from-utc", () -> "2020-01-01T00:00:00Z");
    r.add("msc.time.valid-until-utc", () -> "2100-01-01T00:00:00Z");
  }

  @Autowired TestRestTemplate client;
  @Autowired RabbitTemplate rabbitClient;
  @Autowired StateStore store;
  @Autowired Json json;
  @Autowired JdbcTemplate db;
  @Autowired InboxConsumer inbox;

  Observation observation() {
    return new Observation(
        UUID.randomUUID(),
        "norad-63229",
        UUID.randomUUID().toString(),
        "SIMULATION",
        2,
        "a".repeat(64),
        List.of(new CommandEvidence("image-1", ModeledOutcome.EFFECT_APPLIED, 100, "b".repeat(64))),
        MissionInstant.tai(1000),
        MissionInstant.tai(1001));
  }

  ServiceEvent event(Observation body) {
    return new ServiceEvent(
        UUID.randomUUID(),
        1,
        "SpacecraftExecutionObserved",
        body.spacecraftId(),
        1,
        UUID.randomUUID(),
        null,
        MissionInstant.tai(2000),
        json.tree(body));
  }

  Message message(ServiceEvent event) {
    return new Message(json.write(event).getBytes(StandardCharsets.UTF_8));
  }

  void publish(ServiceEvent event) {
    rabbitClient.send(EventTopology.EXCHANGE, event.type(), message(event));
  }

  String key(Observation o) {
    return o.scenarioId() + ":" + o.loadId();
  }

  StateStore.State<ExecutionEvidenceApi.Evidence> awaitEvidence(Observation o) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertTrue(
                    store
                        .find(
                            "simulation-execution-evidence",
                            key(o),
                            ExecutionEvidenceApi.Evidence.class)
                        .isPresent()));
    return store.require(
        "simulation-execution-evidence", key(o), ExecutionEvidenceApi.Evidence.class);
  }

  @Test
  void realBrokerDeliversEvidenceOnceAndHttpPreservesUnboundScope() {
    var o = observation();
    var e = event(o);
    publish(e);
    var saved = awaitEvidence(o);
    assertEquals(o, saved.body().observation());
    assertEquals(e.eventId(), saved.body().sourceEventId());
    assertEquals(
        ExecutionEvidenceApi.BindingStatus.UNBOUND_SIMULATION_EVIDENCE,
        saved.body().bindingStatus());
    publish(e);
    var retry = event(o);
    publish(retry);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    db.queryForObject(
                        "SELECT count(*) FROM inbox WHERE event_id=?",
                        Integer.class,
                        retry.eventId())));
    assertEquals(1, store.history("simulation-execution-evidence", key(o)).size());
    String path = "/api/simulation-execution-evidence/" + o.scenarioId() + "/" + o.loadId();
    for (var actor : List.of("admin", "operator1", "service")) {
      var response =
          client
              .withBasicAuth(actor, PASSWORD)
              .getForEntity(path, com.fasterxml.jackson.databind.JsonNode.class);
      assertEquals(200, response.getStatusCode().value());
      assertEquals(
          "UNBOUND_SIMULATION_EVIDENCE", response.getBody().at("/body/bindingStatus").asText());
    }
    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity(path, String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void conflictingPayloadAndEnvelopeMismatchRollbackInboxWithoutOverwritingEvidence() {
    var o = observation();
    var e = event(o);
    publish(e);
    var saved = awaitEvidence(o);
    var changed =
        new Observation(
            o.scenarioId(),
            o.spacecraftId(),
            o.loadId(),
            o.environment(),
            3,
            "c".repeat(64),
            o.commands(),
            o.simulatedObservedAt(),
            o.simulatedReceivedAt());
    var conflict = event(changed);
    assertThrows(ApiException.class, () -> inbox.consume(message(conflict)));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM inbox WHERE event_id=?", Integer.class, conflict.eventId()));
    var mismatch =
        new ServiceEvent(
            UUID.randomUUID(),
            1,
            e.type(),
            "other-craft",
            1,
            UUID.randomUUID(),
            null,
            e.occurredAt(),
            e.payload());
    assertThrows(ApiException.class, () -> inbox.consume(message(mismatch)));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM inbox WHERE event_id=?", Integer.class, mismatch.eventId()));
    assertEquals(
        saved,
        store.require(
            "simulation-execution-evidence", key(o), ExecutionEvidenceApi.Evidence.class));
    assertEquals(1, store.history("simulation-execution-evidence", key(o)).size());
  }

  @Test
  void scheduleInputsRemainDurableUntilPreparationWorkflowExists() {
    var event =
        new ServiceEvent(
            UUID.randomUUID(),
            1,
            "ScheduleVersionCommitted",
            "synthetic-craft",
            1,
            UUID.randomUUID(),
            null,
            MissionInstant.tai(2000),
            json.tree(Map.of("source", "synthetic-test")));
    publish(event);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertTrue(
                    store
                        .find(
                            "deferred-control-input",
                            event.eventId().toString(),
                            ServiceEvent.class)
                        .isPresent()));
    assertEquals(
        event,
        store
            .require("deferred-control-input", event.eventId().toString(), ServiceEvent.class)
            .body());
  }

  @org.springframework.test.context.bean.override.mockito.MockitoBean ServiceHttp owners;

  @Test
  void approvalsBindActorAndChecksumAndSupportVersionedRevocation() {
    var prepared = new CommandCompiler(json).compile(
        new msc.domain.shared.Ids.CommandLoadId(UUID.randomUUID().toString()), CommandCompilerTest.fixture());
    String id = prepared.load().id().value();
    store.transaction(() -> store.create("prepared-command-load", id, prepared));
    var api = new CommandApprovalApi(store, () -> MissionInstant.tai(990));
    var one = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator1", "unused");
    var two = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator2", "unused");
    var grant = new CommandApprovalApi.Grant(prepared.load().checksum(), MissionInstant.tai(999), 0);
    var first = api.grant(id, grant, "grant-one", one);
    assertEquals(json.fingerprint(first), json.fingerprint(api.grant(id, grant, "grant-one", one)));
    api.grant(id, grant, "grant-two", two);
    assertEquals(2, api.read(id).size());
    var check = new CommandCatalogApprovalApi(store, json, () -> MissionInstant.tai(990));
    assertTrue(check.check(id).reasons().isEmpty());
    var approval = store.require(CommandApprovalApi.kind(id), "operator1",
        msc.domain.spacecraftcontrol.CommandReleasePolicy.Approval.class).body();
    assertEquals(msc.domain.spacecraftcontrol.CommandReleasePolicy.Binding.of(prepared.load()), approval.binding());
    assertEquals("operator1", approval.actorId());
    assertEquals(msc.domain.spacecraftcontrol.CommandReleasePolicy.ApprovalKind.HUMAN, approval.kind());
    assertThrows(ApiException.class, () -> api.grant(id,
        new CommandApprovalApi.Grant("wrong", MissionInstant.tai(999), 1), "wrong", one));
    assertThrows(ApiException.class, () -> api.grant(id,
        new CommandApprovalApi.Grant(prepared.load().checksum(), MissionInstant.tai(1006), 1), "late", one));
    assertThrows(ApiException.class, () -> api.revoke(id, new CommandApprovalApi.Revoke(2), "stale", one));
    var revoked = api.revoke(id, new CommandApprovalApi.Revoke(1), "revoke", one);
    assertEquals(json.fingerprint(revoked), json.fingerprint(api.revoke(id, new CommandApprovalApi.Revoke(1), "revoke", one)));
    assertTrue(store.require(CommandApprovalApi.kind(id), "operator1",
        msc.domain.spacecraftcontrol.CommandReleasePolicy.Approval.class).body().revoked());
    assertFalse(store.require(CommandApprovalApi.kind(id), "operator2",
        msc.domain.spacecraftcontrol.CommandReleasePolicy.Approval.class).body().revoked());
    api.revoke(id, new CommandApprovalApi.Revoke(1), "revoke-two", two);
    assertEquals(Set.of(msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason.APPROVAL_MISSING), check.check(id).reasons());
    api.grant(id, new CommandApprovalApi.Grant(prepared.load().checksum(), MissionInstant.tai(999), 2), "renew", one);
    assertTrue(check.check(id).reasons().isEmpty());
    assertTrue(new CommandCatalogApprovalApi(store, json, () -> MissionInstant.tai(999))
        .check(id).reasons().contains(msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason.APPROVAL_MISSING));
    assertEquals(3, store.history(CommandApprovalApi.kind(id), "operator1").size());
    assertTrue(store.require("prepared-command-load", id, CommandCompiler.Prepared.class)
        .body().load().authorizationEvidenceReference().isEmpty());
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    for (String user : List.of("requester", "service")) {
      assertEquals(403, client.withBasicAuth(user, PASSWORD).postForEntity(
          "/api/command-loads/" + id + "/approvals",
          new org.springframework.http.HttpEntity<>(grant, headers), String.class).getStatusCode().value());
    }
  }

  @Test
  void currentOwnerAuthorityAndFreshTelemetryCombineWithStoredHumanApprovals() {
    var prepared = new CommandCompiler(json).compile(
        new msc.domain.shared.Ids.CommandLoadId(UUID.randomUUID().toString()), CommandCompilerTest.fixture(true));
    String id = prepared.load().id().value();
    store.transaction(() -> store.create("prepared-command-load", id, prepared));
    var catalog = prepared.sources().catalogsByActivity().values().iterator().next();
    var context = new msc.contracts.AuthorityContracts.Context(catalog.template().operation(),
        msc.domain.anomaly.MissionPhase.ROUTINE, "NOMINAL", catalog.activity().riskClass());
    var policy = new msc.contracts.AuthorityContracts.Policy("sat", "m1", List.of(
        new msc.contracts.AuthorityContracts.Rule(context,
            msc.domain.missiondefinition.AuthorityPolicy.Requirement.TWO_PERSON_APPROVAL)), "test");
    var model = new msc.contracts.SimulationPlanningContracts.Model("sat", "m1", "SIMULATION",
        msc.domain.anomaly.MissionPhase.ROUTINE, "NOMINAL", 1000, 1, 30, 10, true, 1, 2, 0, .5, 0, "test");
    var binding = new msc.domain.monitoring.OperationalTelemetry.Binding("sat", 1, "simulator:test",
        msc.domain.monitoring.OperationalTelemetry.Environment.SIMULATION, 3, 0, "test");
    var frame = new msc.domain.monitoring.OperationalTelemetry.Frame(UUID.randomUUID(), "sat", 1,
        "simulator:test", 1, MissionInstant.tai(990), msc.domain.monitoring.TelemetryObservation.Quality.GOOD,
        msc.domain.monitoring.OperationalTelemetry.Mode.NOMINAL, 100, 0, 1, "test");
    var estimate = msc.domain.monitoring.OperationalTelemetry.Estimate.empty(binding)
        .observe(frame, MissionInstant.tai(990)).estimate();
    org.mockito.Mockito.when(owners.get("mission-definition", "/internal/authority-policies/sat", com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sat", 1, policy)));
    org.mockito.Mockito.when(owners.get("mission-definition", "/internal/simulation-planning-models/sat", com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sat", 1, model)));
    org.mockito.Mockito.when(owners.get("monitoring", "/internal/spacecraft-estimates/sat", com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(Map.of("estimate", new StateStore.State<>("sat", 1, estimate))));
    var now = new java.util.concurrent.atomic.AtomicReference<>(MissionInstant.tai(990));
    var check = new CommandAuthorityCheckApi(store, owners, json, now::get);
    var grants = new CommandApprovalApi(store, now::get);
    var request = new CommandApprovalApi.Grant(prepared.load().checksum(), MissionInstant.tai(999), 0);
    var one = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator1", "unused");
    var two = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator2", "unused");
    grants.grant(id, request, "one", one);
    assertTrue(check.check(id).reasons().contains(msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason.APPROVAL_MISSING));
    grants.grant(id, request, "two", two);
    assertTrue(check.check(id).reasons().isEmpty());
    now.set(MissionInstant.tai(993));
    assertTrue(check.check(id).reasons().contains(msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason.STALE_CONTEXT));
    now.set(MissionInstant.tai(990));
    org.mockito.Mockito.when(owners.get("mission-definition", "/internal/authority-policies/sat", com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sat", 2,
            new msc.contracts.AuthorityContracts.Policy("sat", "m1", List.of(), "deny all"))));
    assertTrue(check.check(id).reasons().contains(msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason.AUTO_FORBIDDEN));
    assertEquals(403, client.withBasicAuth("requester", PASSWORD).postForEntity(
        "/api/command-loads/" + id + "/authority-check", null, String.class).getStatusCode().value());
  }

  @Test
  void preparesOnlyOwnerBoundSourcesAndReplaysPersistedArtifact() {
    var source = CommandCompilerTest.fixture();
    org.mockito.Mockito.when(
            owners.post(
                org.mockito.ArgumentMatchers.eq("planning"),
                org.mockito.ArgumentMatchers.eq("/internal/planning/schedules/query"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(
                    msc.domain.planning.MissionSchedule.Snapshot.class)))
        .thenReturn(source.schedule());
    org.mockito.Mockito.when(
            owners.get(
                "mission-definition",
                "/internal/missions/sat",
                msc.contracts.CatalogContracts.MissionProfile.class))
        .thenReturn(source.mission());
    org.mockito.Mockito.when(
            owners.get(
                "mission-definition",
                "/internal/simulation-time-correlations/sat/versions/2",
                com.fasterxml.jackson.databind.JsonNode.class))
        .thenReturn(json.tree(source.correlation()));
    org.mockito.Mockito.when(
            owners.get(
                "mission-definition",
                "/internal/catalog/image/versions/1",
                msc.contracts.CatalogContracts.CatalogEntry.class))
        .thenReturn(source.catalogsByActivity().get("activity-0"));
    var request =
        new CommandPreparationApi.Prepare(
            new msc.domain.shared.Ids.CommandLoadId(UUID.randomUUID().toString()),
            source.schedule().key(),
            2,
            2,
            Map.of(
                "activity-0",
                new msc.contracts.MissionCatalogBindingContracts.CatalogReference("image", 1),
                "activity-1",
                new msc.contracts.MissionCatalogBindingContracts.CatalogReference("image", 1)),
            source.parametersByActivity(),
            source.deadline());
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    var body = new org.springframework.http.HttpEntity<>(request, headers);
    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .postForEntity("/api/command-loads/prepare", body, String.class)
            .getStatusCode()
            .value());
    var response =
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity(
                "/api/command-loads/prepare", body, com.fasterxml.jackson.databind.JsonNode.class);
    assertEquals(200, response.getStatusCode().value());
    var prepared = json.convert(response.getBody().get("body"), CommandCompiler.Prepared.class);
    assertTrue(prepared.load().authorizationEvidenceReference().isEmpty());
    org.mockito.Mockito.reset(owners);
    assertEquals(
        response.getBody(),
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity(
                "/api/command-loads/prepare", body, com.fasterxml.jackson.databind.JsonNode.class)
            .getBody());
    org.mockito.Mockito.verifyNoInteractions(owners);
    assertEquals(1, store.history("prepared-command-load", request.id().value()).size());
    assertEquals(
        prepared, new CommandPreparationApi(store, owners, json).read(request.id().value()).body());
  }

  @Test
  void missingOwnerScheduleIs404NotPreparedArtifact() {
    var s = CommandCompilerTest.fixture();
    org.mockito.Mockito.when(
            owners.post(
                org.mockito.ArgumentMatchers.eq("planning"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(
                    msc.domain.planning.MissionSchedule.Snapshot.class)))
        .thenThrow(
            org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "missing",
                new org.springframework.http.HttpHeaders(),
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8));
    var id = new msc.domain.shared.Ids.CommandLoadId(UUID.randomUUID().toString());
    var request =
        new CommandPreparationApi.Prepare(
            id,
            s.schedule().key(),
            2,
            2,
            Map.of(
                "activity-0",
                new msc.contracts.MissionCatalogBindingContracts.CatalogReference("image", 1)),
            Map.of("activity-0", Map.of("exposure", "3")),
            s.deadline());
    var headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    assertEquals(
        404,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity(
                "/api/command-loads/prepare",
                new org.springframework.http.HttpEntity<>(request, headers),
                String.class)
            .getStatusCode()
            .value());
    assertTrue(
        store.find("prepared-command-load", id.value(), CommandCompiler.Prepared.class).isEmpty());
  }
}
