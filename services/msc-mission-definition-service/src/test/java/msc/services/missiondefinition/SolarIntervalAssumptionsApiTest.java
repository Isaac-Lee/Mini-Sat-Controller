package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SolarIntervalContracts.*;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SolarIntervalAssumptionsApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Container
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management-alpine");

  static final String PASSWORD = "test-only-not-a-deployed-credential";

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
    for (var role : new String[] {"admin", "operator", "requester", "service"})
      r.add("msc.security.local." + role + "-password", () -> PASSWORD);
    r.add("msc.time.source", () -> "test-only-offset-v1");
    r.add("msc.time.utc-tai-offset-seconds", () -> 37);
    r.add("msc.time.valid-from-utc", () -> "2020-01-01T00:00:00Z");
    r.add("msc.time.valid-until-utc", () -> "2100-01-01T00:00:00Z");
  }

  @Autowired TestRestTemplate client;
  @Autowired msc.platform.StateStore store;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler;

  @org.junit.jupiter.api.AfterEach
  void stopBackgroundPublisher() {
    scheduler.shutdown();
  }

  private void seedMission(String craft, String missionDefinitionVersion, String timeCorrelationId) {
    store.transaction(
        () ->
            store.create(
                "mission",
                craft,
                new MissionProfile(
                    craft,
                    "legacy-catalog-" + craft,
                    1,
                    missionDefinitionVersion,
                    100,
                    20,
                    1000,
                    1,
                    10,
                    timeCorrelationId,
                    "synthetic-test-fixture")));
  }

  private static HttpHeaders jsonHeaders(String idempotencyKey) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  @Test
  void publishesExactAssumptionVersionsWithMissionAndRoleChecks() throws Exception {
    String craft = UUID.randomUUID().toString();
    seedMission(craft, "mission-1", "clock-1");
    var assumption = new Assumptions(craft, "mission-1", "SIMULATION", "solar-model",
        "a".repeat(64), new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
        new msc.contracts.IlluminationContracts.Aoi("extent", -10, 10, -10, 10, 0),
        .001, .0001, 10, "synthetic explicit assumption");
    var body = new HttpEntity<>(new Publish(0, assumption), jsonHeaders("first-" + craft));
    String api = "/api/solar-interval-assumptions";
    for (String role : java.util.List.of("requester", "operator1", "service"))
      assertEquals(403, client.withBasicAuth(role, PASSWORD).postForEntity(api, body, String.class).getStatusCode().value());
    var first = client.withBasicAuth("admin", PASSWORD).postForEntity(api, body, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(assumption, mapper.treeToValue(first.getBody().get("body"), Assumptions.class));
    var second = client.withBasicAuth("admin", PASSWORD).postForEntity(api,
        new HttpEntity<>(new Publish(1, assumption), jsonHeaders("second-" + craft)), JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asLong());
    var replay = client.withBasicAuth("admin", PASSWORD).postForEntity(api, body, JsonNode.class);
    assertEquals(1, replay.getBody().path("version").asLong());
    String path = "/internal/solar-interval-assumptions/" + craft;
    assertEquals(1, client.withBasicAuth("service", PASSWORD).getForObject(path + "/versions/1", JsonNode.class).path("version").asLong());
    assertEquals(2, client.withBasicAuth("service", PASSWORD).getForObject(path, JsonNode.class).path("version").asLong());
    assertEquals(409, client.withBasicAuth("admin", PASSWORD).postForEntity(api,
        new HttpEntity<>(new Publish(0, assumption), jsonHeaders("stale-" + craft)), String.class).getStatusCode().value());
    var wrong = new Assumptions(craft, "different", "SIMULATION", "solar-model", "a".repeat(64),
        assumption.validInterval(), assumption.extent(), .001, .0001, 10, "wrong mission");
    assertEquals(400, client.withBasicAuth("admin", PASSWORD).postForEntity(api,
        new HttpEntity<>(new Publish(2, wrong), jsonHeaders("wrong-" + craft)), String.class).getStatusCode().value());
    assertEquals(2, store.history("solar-interval-assumptions", craft).size());
    assertEquals(403, client.withBasicAuth("requester", PASSWORD).getForEntity(api + "/" + craft, String.class).getStatusCode().value());
  }
}
