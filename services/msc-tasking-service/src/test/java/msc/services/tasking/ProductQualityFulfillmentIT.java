package msc.services.tasking;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import msc.contracts.TaskingContracts.QualityEvidence;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

/** Real broker delivery of Product's L0 integrity-only assessment into the request workflow. */
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductQualityFulfillmentIT {
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
  @Autowired Json json;
  @Autowired JdbcTemplate db;

  JsonNode internal(String id) {
    return client
        .withBasicAuth("service", PASSWORD)
        .getForObject("/internal/requests/" + id, JsonNode.class);
  }

  String status(String id) {
    return internal(id).path("request").path("status").asText();
  }

  /** Same envelope/payload shape that Product's SimulationL0Api writes to its outbox. */
  ServiceEvent assessment(String id, double coverage, double cloud) {
    var evidence =
        new QualityEvidence(
            id,
            1,
            "simulation-l0:" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            coverage,
            cloud,
            "s3://msc-product/" + "a".repeat(64),
            "INTEGRITY_ONLY_SYNTHETIC",
            "SIMULATION");
    return new ServiceEvent(
        UUID.randomUUID(),
        1,
        "ProductQualityAssessed",
        evidence.productReference(),
        1,
        UUID.randomUUID(),
        null,
        MissionInstant.tai(2000),
        json.tree(evidence));
  }

  void publish(ServiceEvent event) {
    rabbitClient.send(
        EventTopology.EXCHANGE,
        event.type(),
        new Message(json.write(event).getBytes(StandardCharsets.UTF_8)));
  }

  void awaitInbox(ServiceEvent event) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    db.queryForObject(
                        "SELECT count(*) FROM inbox WHERE event_id=?",
                        Integer.class,
                        event.eventId())));
  }

  @Test
  void integrityOnlySyntheticAssessmentMovesRequestToPartialThenFulfilled() {
    var submission =
        Map.of(
            "target",
            "synthetic-l0-target",
            "area",
            Map.of(
                "id",
                "ignored",
                "west",
                127.0,
                "south",
                37.0,
                "east",
                127.1,
                "north",
                37.1,
                "sourceReference",
                "test"),
            "criteria",
            Map.of("minimumCoverageFraction", 0.9, "maximumCloudFraction", 0.2),
            "priority",
            50,
            "preference",
            "AUTO");
    var headers = new HttpHeaders();
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    var created =
        client
            .withBasicAuth("requester", PASSWORD)
            .exchange(
                "/api/requests",
                HttpMethod.POST,
                new HttpEntity<>(submission, headers),
                JsonNode.class);
    assertEquals(200, created.getStatusCode().value(), String.valueOf(created.getBody()));
    String id = created.getBody().path("id").asText();
    // Product's pre-check reads exactly these fields.
    assertEquals(1, internal(id).path("request").path("revision").asLong());
    assertEquals("ACCEPTED", status(id));

    var partial = assessment(id, 0.5, 0.1);
    publish(partial);
    await().atMost(Duration.ofSeconds(15)).until(() -> "PARTIALLY_FULFILLED".equals(status(id)));
    assertTrue(internal(id).path("reason").asText().startsWith("INTEGRITY_ONLY_SYNTHETIC: "));
    publish(partial);
    var cloudy = assessment(id, 1, 0.8);
    publish(cloudy);
    awaitInbox(cloudy);
    assertEquals("PARTIALLY_FULFILLED", status(id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='RequestPartiallyFulfilled'",
            Integer.class));

    publish(assessment(id, 0.95, 0.1));
    await().atMost(Duration.ofSeconds(15)).until(() -> "FULFILLED".equals(status(id)));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='RequestFulfilled'", Integer.class));
  }
}
