package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationTimeCorrelationContracts.*;
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

/**
 * Real HTTP/DB owner tests for {@link SimulationTimeCorrelationApi}, mirroring {@code
 * OperationResourceApiTest}'s structure and container setup exactly. Covers Slice A acceptance
 * checks A1 and A6 from {@code .local/claude-delegation/opus-simulator-execution-plan.md}
 * ("Revision 2" section); A2/A3/A4/A5 (pure conversion correctness) are covered without Spring in
 * {@code SimulationTimeCorrelationContractsTest} in msc-contracts.
 *
 * <p>Per the coordinator's instruction, numeric round-trip assertions here decode the response
 * {@code body} into the typed {@link Correlation} record via Jackson and compare typed values or
 * whole-record equality, rather than asserting {@code JsonNode} equality on numerics (the
 * {@code LongNode} vs {@code IntNode} trap).
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulationTimeCorrelationApiTest {
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

  private static Correlation correlation(
      String craft, String missionVersion, String timeCorrelationId, String approvalReference) {
    return new Correlation(
        craft,
        missionVersion,
        timeCorrelationId,
        "onboard-clock-a",
        MissionInstant.tai(1_000_000),
        500,
        10,
        new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(10_000_000)),
        "SIMULATION",
        approvalReference,
        "synthetic-test-fixture");
  }

  private Correlation decodeBody(JsonNode envelope) throws Exception {
    return mapper.treeToValue(envelope.get("body"), Correlation.class);
  }

  // A1: publish/read-back/CAS. Stale expectedVersion -> 409; version 1 re-reads byte-identical
  // (compared at the decoded-typed-record boundary, not via JsonNode numeric equality).
  @Test
  void publishReadBackAndCasEnforceExactHistory() throws Exception {
    String craft = "stc-cas-" + UUID.randomUUID();
    seedMission(craft, "sim-v1", "tc-1");
    var admin = client.withBasicAuth("admin", PASSWORD);

    var v1 = correlation(craft, "sim-v1", "tc-1", "approval-1");
    var first =
        admin.postForEntity(
            "/api/simulation-time-correlations",
            new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-1")),
            JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());
    assertEquals(v1, decodeBody(first.getBody()));

    // Stale expectedVersion (0) after the head has already moved to version 1.
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/simulation-time-correlations",
                new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-stale")),
                String.class)
            .getStatusCode()
            .value());

    var v2 = correlation(craft, "sim-v1", "tc-1", "approval-2");
    var second =
        admin.postForEntity(
            "/api/simulation-time-correlations",
            new HttpEntity<>(new Publish(1, v2), jsonHeaders("cas-2")),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());
    assertEquals(v2, decodeBody(second.getBody()));

    var service = client.withBasicAuth("service", PASSWORD);
    // Exact historical version 1 is still readable and decodes to the original value.
    var historyResponse =
        service.getForObject(
            "/internal/simulation-time-correlations/" + craft + "/versions/1", JsonNode.class);
    assertEquals(v1, decodeBody(historyResponse));
    assertEquals(1, historyResponse.path("version").asInt());

    // Current is the latest.
    var currentResponse =
        service.getForObject("/internal/simulation-time-correlations/" + craft, JsonNode.class);
    assertEquals(v2, decodeBody(currentResponse));
    assertEquals(2, currentResponse.path("version").asInt());

    // /api paths serve the same content for a permitted role.
    var apiCurrent =
        admin.getForObject("/api/simulation-time-correlations/" + craft, JsonNode.class);
    assertEquals(v2, decodeBody(apiCurrent));
  }

  // Mission binding: missionDefinitionVersion mismatch rejected.
  @Test
  void missionDefinitionVersionMismatchRejected() {
    String craft = "stc-mdv-" + UUID.randomUUID();
    seedMission(craft, "sim-v1", "tc-1");

    var mismatched = correlation(craft, "not-the-configured-version", "tc-1", "approval");
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-time-correlations",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("mdv-1")),
                String.class)
            .getStatusCode()
            .value());
  }

  // Mission binding: the exact MissionProfile.timeCorrelationId string must also match.
  @Test
  void timeCorrelationIdMismatchRejected() {
    String craft = "stc-tcid-" + UUID.randomUUID();
    seedMission(craft, "sim-v1", "tc-configured");

    var mismatched = correlation(craft, "sim-v1", "tc-different", "approval");
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-time-correlations",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("tcid-1")),
                String.class)
            .getStatusCode()
            .value());

    // The matching identity is accepted.
    var matching = correlation(craft, "sim-v1", "tc-configured", "approval");
    assertEquals(
        200,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-time-correlations",
                new HttpEntity<>(new Publish(0, matching), jsonHeaders("tcid-2")),
                JsonNode.class)
            .getStatusCode()
            .value());
  }

  // Same Idempotency-Key with a byte-identical request returns the stored response and creates
  // no extra version; the same key with a CHANGED body is a 409, never a silent overwrite.
  @Test
  void idempotentReplayIsExactAndChangedBodySameKeyConflicts() {
    String craft = "stc-idem-" + UUID.randomUUID();
    seedMission(craft, "sim-v1", "tc-1");
    var admin = client.withBasicAuth("admin", PASSWORD);
    var payload = correlation(craft, "sim-v1", "tc-1", "approval-1");
    var request = new HttpEntity<>(new Publish(0, payload), jsonHeaders("idem-key-1"));

    var first = admin.postForEntity("/api/simulation-time-correlations", request, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    var replay = admin.postForEntity("/api/simulation-time-correlations", request, JsonNode.class);
    assertEquals(first.getBody(), replay.getBody());
    assertEquals(
        1,
        store.list("simulation-time-correlation", 500).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());

    // Same key, different body (different approvalReference) -> 409, stored response unchanged.
    var changedPayload = correlation(craft, "sim-v1", "tc-1", "approval-DIFFERENT");
    var changedRequest =
        new HttpEntity<>(new Publish(0, changedPayload), jsonHeaders("idem-key-1"));
    assertEquals(
        409,
        admin
            .postForEntity("/api/simulation-time-correlations", changedRequest, String.class)
            .getStatusCode()
            .value());
    var stillOriginal =
        client
            .withBasicAuth("service", PASSWORD)
            .getForObject("/internal/simulation-time-correlations/" + craft, JsonNode.class);
    assertEquals(first.getBody(), stillOriginal);
  }

  // A6: role enforcement. Unauthenticated -> 401; OPERATOR cannot publish -> 403; requester cannot
  // read -> 403; SERVICE can read /internal.
  @Test
  void roleEnforcement() {
    String craft = "stc-role-" + UUID.randomUUID();
    seedMission(craft, "sim-v1", "tc-1");
    var payload = correlation(craft, "sim-v1", "tc-1", "approval");
    var request = new HttpEntity<>(new Publish(0, payload), jsonHeaders("role-1"));

    assertEquals(
        401,
        client.postForEntity("/api/simulation-time-correlations", request, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/simulation-time-correlations", request, String.class)
            .getStatusCode()
            .value());

    var admin = client.withBasicAuth("admin", PASSWORD);
    assertEquals(
        200,
        admin.postForEntity("/api/simulation-time-correlations", request, JsonNode.class)
            .getStatusCode()
            .value());

    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity("/api/simulation-time-correlations/" + craft, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        client
            .withBasicAuth("service", PASSWORD)
            .getForEntity("/internal/simulation-time-correlations/" + craft, String.class)
            .getStatusCode()
            .value());
  }

  // Missing mission (no CatalogContracts.MissionProfile stored for the spacecraft) -> 404.
  @Test
  void missingMissionRejected() {
    String craft = "stc-missing-mission-" + UUID.randomUUID();
    var payload = correlation(craft, "sim-v1", "tc-1", "approval");
    assertEquals(
        404,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/simulation-time-correlations",
                new HttpEntity<>(new Publish(0, payload), jsonHeaders("missing-mission")),
                String.class)
            .getStatusCode()
            .value());
  }
}
