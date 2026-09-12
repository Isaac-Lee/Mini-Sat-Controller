package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.CatalogContracts.CommandTemplate;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.CatalogContracts.ResourceProfile;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.domain.shared.Ids.ResourceId;
import msc.platform.Json;
import msc.platform.StateStore;
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
 * Regression coverage for the opt-in legacy-replay rescue at CatalogApi.create (see
 * StateStore.legacyReplay / StateStoreLegacyReplayTest for the pure-logic unit tests of the same
 * mechanism). These tests exercise it end to end: a real Postgres `idempotency` row, seeded
 * directly over JDBC the way .local/claude-delegation/opus-set-order-review.md's refinement
 * proposes, standing in for a row written before Set fields had a canonical order.
 *
 * <p>NOT independently run by this worker (no Maven, no server start permitted here -- see
 * .local/claude-delegation/sonnet-set-order.md). Written to compile against the same
 * Testcontainers/Spring Boot harness CatalogApiTest already uses in this module; run it with the
 * module's normal `mvn test`.
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogApiLegacyReplayTest {
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
  @Autowired StateStore store;
  @Autowired Json json;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler;

  @org.junit.jupiter.api.AfterEach
  void stopBackgroundPublisher() {
    scheduler.shutdown();
  }

  private CatalogEntry entry(Set<ResourceId> exclusiveResources, double durationSeconds) {
    var activity =
        new ActivityDefinition(
            new ActivityDefinitionId("imaging"),
            1,
            "IMAGING_STRIP",
            true,
            exclusiveResources,
            Set.of(MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            AuthorityPolicy.RiskClass.LOW,
            "imaging:1");
    var template = new CommandTemplate("imaging", 1, "IMAGE", Map.of());
    var resources = new ResourceProfile(20, 1, 0);
    return new CatalogEntry(
        "imaging",
        1,
        activity,
        template,
        resources,
        AuthorityPolicy.Requirement.AUTO_ALLOWED,
        durationSeconds,
        "approved-by-test");
  }

  private CatalogEntry baseline() {
    var resources = new LinkedHashSet<ResourceId>();
    resources.add(new ResourceId("antenna"));
    resources.add(new ResourceId("payload"));
    return entry(resources, 10);
  }

  /** Simulates a different replica's Set.copyOf order by reversing a stored JSON array in place. */
  private static void reverseArrayField(JsonNode root, String... path) {
    JsonNode parent = root;
    for (int i = 0; i < path.length - 1; i++) parent = parent.get(path[i]);
    var array = (ArrayNode) parent.get(path[path.length - 1]);
    var items = new ArrayList<JsonNode>();
    array.forEach(items::add);
    Collections.reverse(items);
    array.removeAll();
    items.forEach(array::add);
  }

  /** Seeds an idempotency row exactly as a pre-canonical-order StateStore.idempotent would have. */
  private JsonNode seedLegacyIdempotencyRow(String key, CatalogEntry storedEntry) {
    var legacyBody = json.tree(storedEntry).deepCopy();
    reverseArrayField(legacyBody, "activity", "exclusiveResources");
    String legacyFingerprint = json.fingerprint(legacyBody);
    var storedResponse = json.tree(new StateStore.State<>("imaging:1", 1L, legacyBody));
    jdbc.update(
        "INSERT INTO idempotency(scope,request_key,fingerprint,response) VALUES(?,?,?,?::jsonb)",
        "catalog:admin",
        key,
        legacyFingerprint,
        json.write(storedResponse));
    return storedResponse;
  }

  private int historyCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state_history WHERE kind='catalog' AND id='imaging:1'",
        Integer.class);
  }

  private int outboxCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox WHERE envelope->>'aggregateId'='imaging:1'", Integer.class);
  }

  @Test
  void legacyRetryIsRescuedAndWritesNothingNew() {
    var stored = baseline();
    var storedResponse = seedLegacyIdempotencyRow("legacy-rescue-1", stored);
    int historyBefore = historyCount();
    int outboxBefore = outboxCount();
    var idempotencyBefore = jdbc.queryForMap(
        "SELECT fingerprint,response::text FROM idempotency WHERE scope=? AND request_key=?",
        "catalog:admin", "legacy-rescue-1");

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "legacy-rescue-1");
    var admin = client.withBasicAuth("admin", PASSWORD);

    var retry = admin.postForEntity("/api/catalog", new HttpEntity<>(stored, headers), JsonNode.class);
    assertEquals(200, retry.getStatusCode().value());
    // Compare through the same JSON decoding boundary: an in-memory LongNode and an HTTP
    // IntNode can represent the same JSON integer. Legacy array order must still be preserved.
    assertEquals(json.read(json.write(storedResponse), JsonNode.class), retry.getBody());
    assertEquals(
        "payload",
        retry
            .getBody()
            .get("body")
            .get("activity")
            .get("exclusiveResources")
            .get(0)
            .get("value")
            .asText());

    assertEquals(historyBefore, historyCount(), "rescue must not append state_history");
    assertEquals(outboxBefore, outboxCount(), "rescue must not append an outbox event");
    assertEquals(idempotencyBefore, jdbc.queryForMap(
        "SELECT fingerprint,response::text FROM idempotency WHERE scope=? AND request_key=?",
        "catalog:admin", "legacy-rescue-1"), "rescue must not rewrite the stored replay record");
  }

  @Test
  void corruptedLegacyRowNeverGetsAccepted() {
    var stored = baseline();
    var legacyBody = json.tree(stored).deepCopy();
    reverseArrayField(legacyBody, "activity", "exclusiveResources");
    var storedResponse = json.tree(new StateStore.State<>("imaging:1", 1L, legacyBody));
    // Forged/corrupted: fingerprint does not match response.body at all.
    jdbc.update(
        "INSERT INTO idempotency(scope,request_key,fingerprint,response) VALUES(?,?,?,?::jsonb)",
        "catalog:admin",
        "legacy-corrupted-1",
        "0000000000000000000000000000000000000000000000000000000000000000",
        json.write(storedResponse));

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "legacy-corrupted-1");
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        409,
        admin
            .postForEntity("/api/catalog", new HttpEntity<>(stored, headers), String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void changedScalarStillConflicts() {
    var stored = baseline();
    seedLegacyIdempotencyRow("legacy-scalar-1", stored);
    var changed = entry(stored.activity().exclusiveResources(), 25);

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "legacy-scalar-1");
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        409,
        admin
            .postForEntity("/api/catalog", new HttpEntity<>(changed, headers), String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void changedSetMembershipStillConflicts() {
    var stored = baseline();
    seedLegacyIdempotencyRow("legacy-membership-1", stored);
    var wider = new LinkedHashSet<ResourceId>(stored.activity().exclusiveResources());
    wider.add(new ResourceId("wheel"));
    var changed = entry(wider, 10);

    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "legacy-membership-1");
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        409,
        admin
            .postForEntity("/api/catalog", new HttpEntity<>(changed, headers), String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void missionScopeStaysStrictAndNeverAttemptsReconstruction() {
    // MissionProfile has no Set field, so "legacy reordering" cannot apply to it -- but the point
    // of this test is that CatalogApi.mission passes no reconstructor at all (no scope-string
    // inference), so even a row shaped to pass step (a) of the rescue algorithm never gets a
    // chance to run: any fingerprint mismatch here must still be a hard 409.
    var stored =
        new MissionProfile(
            "craft-legacy-strict", "imaging", 1, "sim-v1", 100, 20, 1000, 1, 10, "sim-time",
            "synthetic-test-fixture");
    var storedBody = json.tree(stored);
    var storedResponse = json.tree(new StateStore.State<>("craft-legacy-strict", 1L, storedBody));
    // Self-consistent (would satisfy the rescue's step (a) if reconstruction were ever attempted
    // for this scope, which it must not be).
    jdbc.update(
        "INSERT INTO idempotency(scope,request_key,fingerprint,response) VALUES(?,?,?,?::jsonb)",
        "mission:admin",
        "mission-strict-1",
        json.fingerprint(storedBody),
        json.write(storedResponse));

    var changed =
        new MissionProfile(
            "craft-legacy-strict", "imaging", 1, "sim-v1", 150, 20, 1000, 1, 10, "sim-time",
            "synthetic-test-fixture");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "mission-strict-1");
    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        409,
        admin
            .postForEntity("/api/missions", new HttpEntity<>(changed, headers), String.class)
            .getStatusCode()
            .value());
  }
}
