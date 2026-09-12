package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationCameraModelContracts.Model;
import msc.contracts.SimulationCameraModelContracts.PointingLaw;
import msc.contracts.SimulationCameraModelContracts.Publish;
import msc.contracts.SimulationPlanningContracts;
import msc.domain.anomaly.MissionPhase;
import org.junit.jupiter.api.Test;
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

/**
 * Owner tests for {@link SimulationCameraModelApi}, the new camera-model-only owner API. This file
 * exercises only the new API and its new contract ({@code SimulationCameraModelContracts}); it is
 * the camera model only — no evaluator, no coverage computation and no Planning wiring exist yet,
 * and none is exercised here. See {@code docs/backend/simulation-camera-model.md}.
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulationCameraModelApiTest {
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
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler;

  @org.junit.jupiter.api.AfterEach
  void stopBackgroundPublisher() {
    scheduler.shutdown();
  }

  private void seedMission(String craft, String missionDefinitionVersion) {
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
                    "sim-time",
                    "synthetic-test-fixture")));
  }

  private void seedMission(String craft) {
    seedMission(craft, "sim-v1");
  }

  /**
   * Seeds a simulation planning model directly, exactly as an already-approved publish would leave
   * it; used only to obtain a real pinned version for the camera model to bind to. Values are
   * clearly synthetic test fixtures, not a real instrument specification.
   */
  private long seedPlanningModel(
      String craft, String missionDefinitionVersion, double maxOffNadir) {
    var model =
        new SimulationPlanningContracts.Model(
            craft,
            missionDefinitionVersion,
            "SIMULATION",
            MissionPhase.ROUTINE,
            "NOMINAL",
            12_000,
            5,
            maxOffNadir,
            20,
            true,
            40,
            200,
            50,
            0.4,
            0.5,
            "test-approved");
    return store
        .transaction(() -> store.create("simulation-planning-model", craft, model))
        .version();
  }

  private static Model camera(
      String craft,
      String missionDefinitionVersion,
      double maxOffNadir,
      long simulationPlanningModelVersion) {
    return new Model(
        craft,
        missionDefinitionVersion,
        "SIMULATION",
        PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
        2.5,
        1.8,
        4096,
        2048,
        5.0,
        maxOffNadir,
        10.0,
        simulationPlanningModelVersion,
        "approval-camera",
        "synthetic-test-fixture");
  }

  private static HttpHeaders jsonHeaders(String idempotencyKey) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  private Model decode(JsonNode envelope) throws Exception {
    return mapper.treeToValue(envelope.get("body"), Model.class);
  }

  // Publish / read-back / exact historical version / CAS conflict, decoded field-by-field.
  @Test
  void publishReadBackHistoricalVersionAndCasConflict() throws Exception {
    String craft = "cam-cas-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 30.0);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var v1 = camera(craft, "sim-v1", 25.0, planVersion);
    var first =
        admin.postForEntity(
            "/api/simulation-camera-models",
            new HttpEntity<>(new Publish(0, v1), jsonHeaders("cam-cas-1")),
            JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());
    assertEquals(v1, decode(first.getBody()));

    // Stale expectedVersion (0) after the head has already moved to version 1.
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, v1), jsonHeaders("cam-cas-stale")),
                String.class)
            .getStatusCode()
            .value());

    var v2 = camera(craft, "sim-v1", 20.0, planVersion);
    var second =
        admin.postForEntity(
            "/api/simulation-camera-models",
            new HttpEntity<>(new Publish(1, v2), jsonHeaders("cam-cas-2")),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());
    assertEquals(v2, decode(second.getBody()));

    var service = client.withBasicAuth("service", PASSWORD);
    var historyOne =
        service.getForObject(
            "/internal/simulation-camera-models/" + craft + "/versions/1", JsonNode.class);
    assertEquals(v1, decode(historyOne));
    var current =
        service.getForObject("/internal/simulation-camera-models/" + craft, JsonNode.class);
    assertEquals(v2, decode(current));
  }

  // Old version retention: version 1 re-reads byte-identical (decoded, field by field) after
  // version 2 is published, even after a third publish moves the head further still.
  @Test
  void oldVersionRetentionAfterMultiplePublishes() throws Exception {
    String craft = "cam-retain-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 30.0);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var v1 = camera(craft, "sim-v1", 15.0, planVersion);
    admin.postForEntity(
        "/api/simulation-camera-models",
        new HttpEntity<>(new Publish(0, v1), jsonHeaders("cam-retain-1")),
        JsonNode.class);
    var v2 = camera(craft, "sim-v1", 16.0, planVersion);
    admin.postForEntity(
        "/api/simulation-camera-models",
        new HttpEntity<>(new Publish(1, v2), jsonHeaders("cam-retain-2")),
        JsonNode.class);
    var v3 = camera(craft, "sim-v1", 17.0, planVersion);
    admin.postForEntity(
        "/api/simulation-camera-models",
        new HttpEntity<>(new Publish(2, v3), jsonHeaders("cam-retain-3")),
        JsonNode.class);

    var service = client.withBasicAuth("service", PASSWORD);
    var readBack =
        service.getForObject(
            "/internal/simulation-camera-models/" + craft + "/versions/1", JsonNode.class);
    assertEquals(v1, decode(readBack));
    assertEquals(1, readBack.path("version").asInt());
  }

  @Test
  void missionDefinitionVersionMismatchRejected() {
    String craft = "cam-mdv-" + UUID.randomUUID();
    seedMission(craft, "sim-v1");
    long planVersion = seedPlanningModel(craft, "sim-v1", 30.0);

    var mismatched = camera(craft, "not-the-configured-mission-version", 20.0, planVersion);
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("cam-mdv-1")),
                String.class)
            .getStatusCode()
            .value());
  }

  // The camera model must pin the exact current simulation planning model version, never trust
  // a caller-supplied one.
  @Test
  void simulationPlanningModelVersionPinMismatchRejected() {
    String craft = "cam-pin-" + UUID.randomUUID();
    seedMission(craft);
    seedPlanningModel(craft, "sim-v1", 30.0);

    var wrongPin = camera(craft, "sim-v1", 20.0, 999L);
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, wrongPin), jsonHeaders("cam-pin-1")),
                String.class)
            .getStatusCode()
            .value());
  }

  // The camera's own maximumOffNadirDegrees cannot exceed the pinned planning model's bound.
  @Test
  void maximumOffNadirExceedingPinnedPlanningModelRejected() {
    String craft = "cam-offnadir-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 15.0);

    var tooWide = camera(craft, "sim-v1", 15.1, planVersion);
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, tooWide), jsonHeaders("cam-offnadir-1")),
                String.class)
            .getStatusCode()
            .value());

    // Exactly equal to the pinned bound is accepted.
    var exact = camera(craft, "sim-v1", 15.0, planVersion);
    assertEquals(
        200,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, exact), jsonHeaders("cam-offnadir-2")),
                JsonNode.class)
            .getStatusCode()
            .value());
  }

  // Every bound the contract's own constructor enforces: non-positive/non-finite half-angles,
  // raster dimensions, GSD ceiling, elevation out of range, environment != "SIMULATION", and a
  // missing pointing law / non-positive planning-model pin.
  @Test
  void everyNumericAndEnumBoundIsEnforcedByTheConstructor() {
    String craft = "cam-bound";
    assertDoesNotThrow(() -> camera(craft, "sim-v1", 20.0, 1));

    // halfAngleAcrossDegrees must be finite, positive, and bounded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                0,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                -1,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                Double.NaN,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                46,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // halfAngleAlongDegrees must be finite, positive, and bounded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                0,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                Double.POSITIVE_INFINITY,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // rasterColumns/rasterRows must be positive bounded integers.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                0,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                -5,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                100_000,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // maximumGroundSampleDistanceMeters must be finite, positive and bounded.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                0,
                20.0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                20_000,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // maximumOffNadirDegrees must be finite, positive and in (0, 60].
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                0,
                10.0,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                61,
                10.0,
                1,
                "a",
                "p"));

    // minimumTargetElevationDegrees must be finite and in [0, 90).
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                -0.1,
                1,
                "a",
                "p"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                90,
                1,
                "a",
                "p"));

    // environment must equal "SIMULATION" exactly.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "HARDWARE",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // pointingLaw is required.
    assertThrows(
        NullPointerException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                null,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                1,
                "a",
                "p"));

    // simulationPlanningModelVersion must be a positive pin, never zero or negative.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                craft,
                "sim-v1",
                "SIMULATION",
                PointingLaw.STARE_TARGET_TANGENT_PLANE_V1,
                2.5,
                1.8,
                4096,
                2048,
                5.0,
                20.0,
                10.0,
                0,
                "a",
                "p"));
  }

  // Role enforcement and idempotent replay.
  @Test
  void roleEnforcementAndIdempotentReplay() {
    String craft = "cam-role-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 30.0);
    var payload = camera(craft, "sim-v1", 20.0, planVersion);
    var request = new HttpEntity<>(new Publish(0, payload), jsonHeaders("cam-role-1"));

    assertEquals(
        401,
        client
            .postForEntity("/api/simulation-camera-models", request, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/simulation-camera-models", request, String.class)
            .getStatusCode()
            .value());

    var admin = client.withBasicAuth("admin", PASSWORD);
    var first = admin.postForEntity("/api/simulation-camera-models", request, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    var replay = admin.postForEntity("/api/simulation-camera-models", request, JsonNode.class);
    assertEquals(first.getBody(), replay.getBody());
    assertEquals(
        1,
        store.list("simulation-camera-model", 500).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());

    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity("/api/simulation-camera-models/" + craft, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        client
            .withBasicAuth("service", PASSWORD)
            .getForEntity("/internal/simulation-camera-models/" + craft, String.class)
            .getStatusCode()
            .value());
  }

  // Same idempotency key reused for a materially different request body must conflict (409),
  // never silently replay a mismatched response.
  @Test
  void sameKeyWithChangedBodyConflicts() {
    String craft = "cam-key-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 30.0);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var original = camera(craft, "sim-v1", 20.0, planVersion);
    var first =
        admin.postForEntity(
            "/api/simulation-camera-models",
            new HttpEntity<>(new Publish(0, original), jsonHeaders("cam-key-reuse")),
            JsonNode.class);
    assertEquals(200, first.getStatusCode().value());

    var changed = camera(craft, "sim-v1", 21.0, planVersion);
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(new Publish(0, changed), jsonHeaders("cam-key-reuse")),
                String.class)
            .getStatusCode()
            .value());
  }

  // Reject the event insertion after state/history writes, then verify the API transaction rolled
  // back.
  @Test
  void actualApiRollbackLeavesNoPartialState() {
    String craft = "cam-rollback-" + UUID.randomUUID();
    seedMission(craft);
    long planVersion = seedPlanningModel(craft, "sim-v1", 10.0);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var model = camera(craft, "sim-v1", 10.0, planVersion);
    jdbc.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_camera_test CHECK (event_type <>"
            + " 'SimulationCameraModelPublished' OR envelope->>'aggregateId' <> '"
            + craft
            + "')");
    try {
      var failed =
          admin.postForEntity(
              "/api/simulation-camera-models",
              new HttpEntity<>(new Publish(0, model), jsonHeaders("cam-rollback-1")),
              String.class);
      assertTrue(failed.getStatusCode().isError());
    } finally {
      jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT reject_camera_test");
    }

    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='simulation-camera-model' AND id=?",
            Integer.class,
            craft));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM state_history WHERE kind='simulation-camera-model' AND id=?",
            Integer.class,
            craft));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='SimulationCameraModelPublished'"
                + " AND envelope->>'aggregateId'=?",
            Integer.class,
            craft));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotency WHERE scope=? AND request_key=?",
            Integer.class,
            "simulation-camera-model:admin",
            "cam-rollback-1"));

    // The identical request/key can be retried after the persistence fault is removed.
    var corrected = camera(craft, "sim-v1", 10.0, planVersion);
    var retried =
        admin.postForEntity(
            "/api/simulation-camera-models",
            new HttpEntity<>(new Publish(0, corrected), jsonHeaders("cam-rollback-1")),
            JsonNode.class);
    assertEquals(200, retried.getStatusCode().value());
    assertEquals(1, retried.getBody().path("version").asInt());
  }

  @Test
  void pinnedPlanningModelMustBelongToSameMissionDefinition() {
    String craft = "cam-planning-mission-" + UUID.randomUUID();
    seedMission(craft, "sim-v2");
    long planVersion = seedPlanningModel(craft, "sim-v1", 30);
    var response =
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-camera-models",
                new HttpEntity<>(
                    new Publish(0, camera(craft, "sim-v2", 25, planVersion)),
                    jsonHeaders("cam-planning-mission-key")),
                String.class);
    assertEquals(400, response.getStatusCode().value());
    assertTrue(store.find("simulation-camera-model", craft, Model.class).isEmpty());
  }
}
