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
}
