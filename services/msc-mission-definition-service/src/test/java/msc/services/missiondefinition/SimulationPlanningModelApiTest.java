package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.contracts.SimulationPlanningContracts.Publish;
import msc.domain.anomaly.MissionPhase;
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

/**
 * Covers the explicit ADMIN-published simulation planning model: CAS/version history/immutability
 * and mission-version binding (B1/B2), every numeric/enum bound rejection (B3), role enforcement
 * (B4) and idempotent replay (B5). Sensor feasibility evaluation and its cross-check against the
 * agility model are explicitly out of scope here and are not exercised by these tests; see
 * docs/backend/simulation-planning-model.md (B6).
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulationPlanningModelApiTest {
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

  private static Model baseModel(String craft, String missionDefinitionVersion) {
    return new Model(
        craft,
        missionDefinitionVersion,
        "SIMULATION",
        MissionPhase.ROUTINE,
        "NOMINAL",
        12_000,
        5,
        30,
        20,
        true,
        40,
        200,
        50,
        0.4,
        0.5,
        "test-approved");
  }

  private void createMission(String craft, String missionDefinitionVersion) {
    store.transaction(
        () ->
            store.create(
                "mission",
                craft,
                new MissionProfile(
                    craft,
                    "imaging",
                    1,
                    missionDefinitionVersion,
                    100,
                    20,
                    1000,
                    1,
                    10,
                    "sim-time",
                    "synthetic-test-fixture")));
  }

  @Test
  void publicationBindsMissionPreservesHistoryAndRejectsStaleWriters() {
    String craft = "sim-splan-" + java.util.UUID.randomUUID();
    createMission(craft, "sim-v1");

    var model = baseModel(craft, "sim-v1");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "splan-1");
    var request = new Publish(0, model);
    var entity = new HttpEntity<>(request, headers);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var first = admin.postForEntity("/api/simulation-planning-models", entity, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());

    // Stale expectedVersion is rejected (409).
    headers.set("Idempotency-Key", "splan-stale");
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/simulation-planning-models", new HttpEntity<>(request, headers), String.class)
            .getStatusCode()
            .value());

    // Correct expectedVersion yields version 2.
    var revised = baseModel(craft, "sim-v1");
    headers.set("Idempotency-Key", "splan-2");
    var second =
        admin.postForEntity(
            "/api/simulation-planning-models",
            new HttpEntity<>(new Publish(1, revised), headers),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());

    var service = client.withBasicAuth("service", PASSWORD);
    // Version 1 remains byte-identical on re-read.
    assertEquals(
        first.getBody(),
        service.getForObject(
            "/internal/simulation-planning-models/" + craft + "/versions/1", JsonNode.class));
    assertEquals(
        second.getBody(),
        service.getForObject("/internal/simulation-planning-models/" + craft, JsonNode.class));

    assertEquals(
        2,
        store.list("simulation-planning-model", 100).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());
  }

  @Test
  void publicationAgainstMismatchedMissionDefinitionVersionIsRejected() {
    String craft = "sim-splan-" + java.util.UUID.randomUUID();
    createMission(craft, "sim-v1");

    var mismatch = baseModel(craft, "different-mission-version");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "splan-mismatch");
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-planning-models",
                new HttpEntity<>(new Publish(0, mismatch), headers),
                String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void roleEnforcementRestrictsPublishAndRead() {
    String craft = "sim-splan-" + java.util.UUID.randomUUID();
    createMission(craft, "sim-v1");
    var model = baseModel(craft, "sim-v1");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "splan-role-1");
    var entity = new HttpEntity<>(new Publish(0, model), headers);

    // OPERATOR cannot publish.
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/simulation-planning-models", entity, String.class)
            .getStatusCode()
            .value());

    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        200,
        admin.postForEntity("/api/simulation-planning-models", entity, JsonNode.class)
            .getStatusCode()
            .value());

    // Requester (below OPERATOR) cannot read.
    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity("/api/simulation-planning-models/" + craft, String.class)
            .getStatusCode()
            .value());

    // Unauthenticated is rejected outright.
    assertEquals(
        401,
        client
            .getForEntity("/api/simulation-planning-models/" + craft, String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void idempotentReplayDoesNotCreateSecondVersion() {
    String craft = "sim-splan-" + java.util.UUID.randomUUID();
    createMission(craft, "sim-v1");
    var model = baseModel(craft, "sim-v1");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "splan-replay");
    var entity = new HttpEntity<>(new Publish(0, model), headers);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var first = admin.postForEntity("/api/simulation-planning-models", entity, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());

    // Same key, same body: replays the same response, no new version.
    var replay = admin.postForEntity("/api/simulation-planning-models", entity, JsonNode.class);
    assertEquals(first.getBody(), replay.getBody());
    assertEquals(
        1,
        store.list("simulation-planning-model", 100).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());
  }

  @Test
  void everyNumericAndEnumBoundIsEnforcedByTheConstructor() {
    String craft = "sim-splan-bound";
    Model ok = baseModel(craft, "sim-v1");
    assertDoesNotThrow(() -> baseModel(craft, "sim-v1"));

    // environment must equal "SIMULATION" exactly.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "HARDWARE", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, 50, 0.4, 0.5, "test"));

    // phase is required.
    assertThrows(
        NullPointerException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", null, "NOMINAL", 12_000, 5, 30, 20, true, 40, 200,
                50, 0.4, 0.5, "test"));

    // mode must be nonblank text.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "  ", 12_000, 5, 30, 20, true,
                40, 200, 50, 0.4, 0.5, "test"));

    // swathWidthMeters must be positive and bounded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 0, 5, 30, 20, true,
                40, 200, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 2_000_000, 5, 30,
                20, true, 40, 200, 50, 0.4, 0.5, "test"));

    // groundSampleDistanceMeters must be positive and bounded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 0, 30, 20,
                true, 40, 200, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 20_000, 30,
                20, true, 40, 200, 50, 0.4, 0.5, "test"));

    // maximumOffNadirDegrees must be in (0, 60].
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 0, 20,
                true, 40, 200, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 61, 20,
                true, 40, 200, 50, 0.4, 0.5, "test"));

    // minimumSunElevationDegrees must be in [0, 90).
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, -1,
                true, 40, 200, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 90,
                true, 40, 200, 50, 0.4, 0.5, "test"));

    // requiresWeatherEvaluation=false is rejected: no implicit clear-sky path.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                false, 40, 200, 50, 0.4, 0.5, "test"));

    // busDrawWatts / sunlitGenerationWatts / eclipseGenerationWatts must be finite and
    // nonnegative.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, -1, 200, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, -1, 50, 0.4, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, -1, 0.4, 0.5, "test"));

    // eclipseGenerationWatts > sunlitGenerationWatts is rejected (conservative model).
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 100, 150, 0.4, 0.5, "test"));

    // worstCaseSunlitFraction must be in [0, 1].
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, 50, -0.1, 0.5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, 50, 1.1, 0.5, "test"));

    // minimumPropellantKg must be finite and nonnegative.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, 50, 0.4, -0.1, "test"));

    // approvalReference must be nonblank text.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft, "sim-v1", "SIMULATION", MissionPhase.ROUTINE, "NOMINAL", 12_000, 5, 30, 20,
                true, 40, 200, 50, 0.4, 0.5, "  "));

    assertNotNull(ok);
  }
}
