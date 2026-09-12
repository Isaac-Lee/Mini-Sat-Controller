package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.AuthorityContracts.*;
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
class AuthorityApiTest {
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
  void publishesVersionedRulesWithRolesReplayAndMissionBinding() throws Exception {
    String craft = UUID.randomUUID().toString();
    seedMission(craft, "mission-1", "clock-1");
    var context = new Context("IMAGE", msc.domain.anomaly.MissionPhase.ROUTINE, "NOMINAL",
        msc.domain.missiondefinition.AuthorityPolicy.RiskClass.LOW);
    var policy = new Policy(craft, "mission-1", java.util.List.of(new Rule(context,
        msc.domain.missiondefinition.AuthorityPolicy.Requirement.TWO_PERSON_APPROVAL)), "test fixture");
    var body = new HttpEntity<>(new Publish(0, policy), jsonHeaders("initial-" + craft));
    for (String role : java.util.List.of("requester", "operator1", "service"))
      assertEquals(403, client.withBasicAuth(role, PASSWORD).postForEntity(
          "/api/authority-policies", body, String.class).getStatusCode().value());
    var first = client.withBasicAuth("admin", PASSWORD).postForEntity(
        "/api/authority-policies", body, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(policy, mapper.treeToValue(first.getBody().get("body"), Policy.class));
    var replay = client.withBasicAuth("admin", PASSWORD).postForEntity(
        "/api/authority-policies", body, JsonNode.class);
    assertEquals(policy, mapper.treeToValue(replay.getBody().get("body"), Policy.class));
    var denied = new Policy(craft, "mission-1", java.util.List.of(), "revised to deny");
    var update = client.withBasicAuth("admin", PASSWORD).postForEntity("/api/authority-policies",
        new HttpEntity<>(new Publish(1, denied), jsonHeaders("update-" + craft)), JsonNode.class);
    assertEquals(200, update.getStatusCode().value());
    assertEquals(2, update.getBody().path("version").asLong());
    String path = "/internal/authority-policies/" + craft;
    assertEquals(policy, mapper.treeToValue(client.withBasicAuth("service", PASSWORD)
        .getForObject(path + "/versions/1", JsonNode.class).get("body"), Policy.class));
    assertEquals(denied, mapper.treeToValue(client.withBasicAuth("service", PASSWORD)
        .getForObject(path, JsonNode.class).get("body"), Policy.class));
    assertEquals(409, client.withBasicAuth("admin", PASSWORD).postForEntity("/api/authority-policies",
        new HttpEntity<>(new Publish(1, policy), jsonHeaders("stale-" + craft)), String.class).getStatusCode().value());
    assertEquals(400, client.withBasicAuth("admin", PASSWORD).postForEntity("/api/authority-policies",
        new HttpEntity<>(new Publish(2, new Policy(craft, "wrong", java.util.List.of(), "invalid")),
            jsonHeaders("wrong-" + craft)), String.class).getStatusCode().value());
    assertEquals(2, store.history("authority-policy", craft).size());
    assertEquals(403, client.withBasicAuth("requester", PASSWORD).getForEntity(
        "/api/authority-policies/" + craft, String.class).getStatusCode().value());
  }
}
