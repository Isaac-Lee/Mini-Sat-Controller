package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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
class CatalogApiTest {
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
  @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler;

  @org.junit.jupiter.api.AfterEach
  void stopBackgroundPublisher() {
    scheduler.shutdown();
  }

  static final String ENTRY =
      """
{"id":"imaging","version":1,"activity":{"id":{"value":"imaging"},"version":1,"name":"IMAGING_STRIP","approved":true,
"exclusiveResources":[{"value":"payload"}],"allowedPhases":["ROUTINE"],"allowedModes":["NOMINAL"],"riskClass":"LOW","commandTemplateReference":"imaging:1"},
"template":{"id":"imaging","version":1,"operation":"IMAGE","parameters":{}},
"resources":{"powerWatts":20,"generatedMegabytes":1,"propellantKilograms":0},
"authority":"AUTO_ALLOWED","durationSeconds":10,"approvalReference":"approved-by-test-operator"}
""";

  @Test
  void agilityPublicationBindsMissionPreservesHistoryAndRejectsStaleWriters() {
    String craft = "sim-agility-" + java.util.UUID.randomUUID();
    store.transaction(
        () ->
            store.create(
                "mission",
                craft,
                new msc.contracts.CatalogContracts.MissionProfile(
                    craft,
                    "imaging",
                    1,
                    "sim-v1",
                    100,
                    20,
                    1000,
                    1,
                    10,
                    "sim-time",
                    "synthetic-test-fixture")));
    var model =
        new msc.contracts.AgilityContracts.Model(
            craft, "sim-v1", "SIMULATION", 30, 2, 5, "test-approved");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "agility-1");
    var request = new msc.contracts.AgilityContracts.Publish(0, model);
    var entity = new HttpEntity<>(request, headers);
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/agility-models", entity, String.class)
            .getStatusCode()
            .value());
    var first = admin.postForEntity("/api/agility-models", entity, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());
    assertEquals(
        first.getBody(),
        admin.postForEntity("/api/agility-models", entity, JsonNode.class).getBody());
    headers.set("Idempotency-Key", "agility-stale");
    assertEquals(
        409,
        admin
            .postForEntity("/api/agility-models", new HttpEntity<>(request, headers), String.class)
            .getStatusCode()
            .value());
    var revised =
        new msc.contracts.AgilityContracts.Model(
            craft, "sim-v1", "SIMULATION", 25, 1, 8, "test-revised");
    headers.set("Idempotency-Key", "agility-2");
    var second =
        admin.postForEntity(
            "/api/agility-models",
            new HttpEntity<>(new msc.contracts.AgilityContracts.Publish(1, revised), headers),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());
    var service = client.withBasicAuth("service", PASSWORD);
    assertEquals(
        first.getBody(),
        service.getForObject("/internal/agility-models/" + craft + "/versions/1", JsonNode.class));
    assertEquals(
        second.getBody(),
        service.getForObject("/internal/agility-models/" + craft, JsonNode.class));
    var mismatch =
        new msc.contracts.AgilityContracts.Model(
            craft, "different-mission", "SIMULATION", 25, 1, 8, "test-revised");
    headers.set("Idempotency-Key", "agility-wrong-mission");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/agility-models",
                new HttpEntity<>(new msc.contracts.AgilityContracts.Publish(2, mismatch), headers),
                String.class)
            .getStatusCode()
            .value());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new msc.contracts.AgilityContracts.Model(
                craft, "sim-v1", "HARDWARE", 30, 2, 5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new msc.contracts.AgilityContracts.Model(
                craft, "sim-v1", "SIMULATION", 30, 0, 5, "test"));
    assertEquals(
        2,
        store.list("agility-model", 100).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());
  }

  @Test
  void authenticatedCatalogIsImmutableAndIdempotent() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "catalog-create-test");
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(401, client.getForEntity("/api/catalog", String.class).getStatusCode().value());
    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .postForEntity("/api/catalog", new HttpEntity<>(ENTRY, headers), String.class)
            .getStatusCode()
            .value());
    var first =
        admin.postForEntity("/api/catalog", new HttpEntity<>(ENTRY, headers), JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().get("version").asInt());
    var duplicate =
        admin.postForEntity("/api/catalog", new HttpEntity<>(ENTRY, headers), JsonNode.class);
    assertEquals(first.getBody(), duplicate.getBody());
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/catalog", new HttpEntity<>(ENTRY.replace("20", "21"), headers), String.class)
            .getStatusCode()
            .value());
    var fetched =
        client
            .withBasicAuth("service", PASSWORD)
            .getForEntity("/internal/catalog/imaging/versions/1", JsonNode.class);
    assertEquals(200, fetched.getStatusCode().value());
    assertEquals("IMAGE", fetched.getBody().get("template").get("operation").asText());
    assertEquals(
        403,
        admin
            .getForEntity("/internal/catalog/imaging/versions/1", String.class)
            .getStatusCode()
            .value());
  }
}
