package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.domain.shared.Ids.ResourceId;
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
 * Owner tests for {@link OperationResourceApi}, the new per-operation resource-evidence API. This
 * file exercises only the new API and its new contracts ({@code OperationResourceContracts}); it
 * does not modify or re-run {@code CatalogApiTest} or {@code MissionCatalogBindingApiTest} — see
 * the handoff for what this agent could and could not run itself. {@code
 * legacyMissionReadIsUnaffectedByPublication} below is a same-module demonstration that publishing
 * operation resource profiles does not disturb the legacy {@code MissionProfile} read path, not a
 * substitute for running those other test classes.
 *
 * <p>Acceptance checks C1-C8 from {@code .local/claude-delegation/opus-operation-resource-plan.md}
 * section 5 are covered below, with C5 adjusted per the coordinator's corrections: a {@code
 * DOWNLINK} profile with nonzero approved {@code generatedMegabytes} alongside an explicit drain
 * is accepted (not rejected), and a zero-propellant {@code MANEUVER} entry is accepted (not
 * rejected) — see {@code downlinkWithNonzeroGeneratedDataAcceptedAndRoundTrips} and {@code
 * maneuverWithZeroPropellantAccepted}.
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationResourceApiTest {
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

  private static CatalogEntry catalogEntry(
      String activityId, long version, String operation, ResourceProfile resources) {
    var activity =
        new ActivityDefinition(
            new ActivityDefinitionId(activityId),
            version,
            "NAME_" + activityId,
            true,
            Set.of(new ResourceId("resource-" + activityId)),
            Set.of(MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            AuthorityPolicy.RiskClass.LOW,
            activityId + ":" + version);
    var template = new CommandTemplate(activityId, version, operation, Map.of());
    return new CatalogEntry(
        activityId,
        version,
        activity,
        template,
        resources,
        AuthorityPolicy.Requirement.AUTO_ALLOWED,
        10,
        "test-approved");
  }

  private void seedCatalog(String activityId, long version, String operation, ResourceProfile r) {
    var entry = catalogEntry(activityId, version, operation, r);
    store.transaction(() -> store.create("catalog", activityId + ":" + version, entry));
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

  private static HttpHeaders jsonHeaders(String idempotencyKey) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  private static ExpectedCatalogResources expectedOf(ResourceProfile r) {
    return new ExpectedCatalogResources(
        r.powerWatts(), r.generatedMegabytes(), r.propellantKilograms());
  }

  private static OperationResourceProfile imageProfile(
      String catalogId, long version, ResourceProfile r) {
    return new OperationResourceProfile(
        Operation.IMAGE, new CatalogReference(catalogId, version), expectedOf(r), null);
  }

  private static OperationResourceProfile downlinkProfile(
      String catalogId, long version, ResourceProfile r, double ratePerSecond) {
    return new OperationResourceProfile(
        Operation.DOWNLINK,
        new CatalogReference(catalogId, version),
        expectedOf(r),
        ratePerSecond);
  }

  private static OperationResourceProfile maneuverProfile(
      String catalogId, long version, ResourceProfile r) {
    return new OperationResourceProfile(
        Operation.MANEUVER, new CatalogReference(catalogId, version), expectedOf(r), null);
  }

  // C1: publish/read-back/CAS. Stale expectedVersion -> 409; correct one yields version 2;
  // version 1 re-reads byte-identical.
  @Test
  void publishReadBackAndCasEnforceExactHistory() {
    String craft = "orp-cas-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    String maneuverId = "maneuver-" + craft;
    var imagingResources = new ResourceProfile(50, 10, 0);
    var downlinkResources = new ResourceProfile(30, 0, 0);
    var maneuverResources = new ResourceProfile(20, 0, 2);
    seedCatalog(imagingId, 1, "IMAGE", imagingResources);
    seedCatalog(downlinkId, 1, "DOWNLINK", downlinkResources);
    seedCatalog(maneuverId, 1, "MANEUVER", maneuverResources);
    seedMission(craft);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var v1 =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(
                imageProfile(imagingId, 1, imagingResources),
                downlinkProfile(downlinkId, 1, downlinkResources, 5.0)),
            "approval-1",
            "t");
    var first =
        admin.postForEntity(
            "/api/operation-resource-profiles",
            new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-1")),
            JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());

    // Stale expectedVersion (0) after the head has already moved to version 1.
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-stale")),
                String.class)
            .getStatusCode()
            .value());

    var v2 =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(
                imageProfile(imagingId, 1, imagingResources),
                downlinkProfile(downlinkId, 1, downlinkResources, 5.0),
                maneuverProfile(maneuverId, 1, maneuverResources)),
            "approval-2",
            "t");
    var second =
        admin.postForEntity(
            "/api/operation-resource-profiles",
            new HttpEntity<>(new Publish(1, v2), jsonHeaders("cas-2")),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());

    var service = client.withBasicAuth("service", PASSWORD);
    assertEquals(
        first.getBody(),
        service.getForObject(
            "/internal/operation-resource-profiles/" + craft + "/versions/1", JsonNode.class));
    assertEquals(
        second.getBody(),
        service.getForObject("/internal/operation-resource-profiles/" + craft, JsonNode.class));
  }

  // C2: missionDefinitionVersion mismatch rejected.
  @Test
  void missionDefinitionVersionMismatchRejected() {
    String craft = "orp-mdv-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    var resources = new ResourceProfile(50, 10, 0);
    seedCatalog(imagingId, 1, "IMAGE", resources);
    seedMission(craft, "sim-v1");

    var mismatched =
        new Profiles(
            craft,
            "not-the-configured-mission-version",
            "SIMULATION",
            List.of(imageProfile(imagingId, 1, resources)),
            "approval",
            "t");
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("mdv-1")),
                String.class)
            .getStatusCode()
            .value());
  }

  // C3: operation mismatch rejected. This is the central invariant of the slice: a catalog entry
  // that actually declares IMAGE must not be accepted as evidence for a DOWNLINK profile merely
  // because it was wrapped in one.
  @Test
  void operationMismatchRejected() {
    String craft = "orp-op-mismatch-" + UUID.randomUUID();
    String wrongOpId = "wrong-op-" + craft;
    var resources = new ResourceProfile(30, 5, 0);
    seedCatalog(wrongOpId, 1, "IMAGE", resources); // declares IMAGE, bound to DOWNLINK below
    seedMission(craft);

    var mismatched =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(downlinkProfile(wrongOpId, 1, resources, 5.0)),
            "approval",
            "t");
    assertEquals(
        400,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("op-mismatch")),
                String.class)
            .getStatusCode()
            .value());
  }

  // C4: `expected` disagreeing with the resolved CatalogEntry.resources() rejected, field by
  // field. `expected` is a tripwire, never a second source of truth: none of these three
  // mismatches is silently accepted or averaged.
  @Test
  void expectedResourcesMismatchRejectedFieldByField() {
    String craft = "orp-expected-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    var actual = new ResourceProfile(50, 10, 2);
    seedCatalog(imagingId, 1, "IMAGE", actual);
    seedMission(craft);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var wrongPower =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(
                new OperationResourceProfile(
                    Operation.IMAGE,
                    new CatalogReference(imagingId, 1),
                    new ExpectedCatalogResources(999, 10, 2),
                    null)),
            "approval",
            "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, wrongPower), jsonHeaders("expected-power")),
                String.class)
            .getStatusCode()
            .value());

    var wrongGenerated =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(
                new OperationResourceProfile(
                    Operation.IMAGE,
                    new CatalogReference(imagingId, 1),
                    new ExpectedCatalogResources(50, 999, 2),
                    null)),
            "approval",
            "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, wrongGenerated), jsonHeaders("expected-gen")),
                String.class)
            .getStatusCode()
            .value());

    var wrongPropellant =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(
                new OperationResourceProfile(
                    Operation.IMAGE,
                    new CatalogReference(imagingId, 1),
                    new ExpectedCatalogResources(50, 10, 999),
                    null)),
            "approval",
            "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, wrongPropellant), jsonHeaders("expected-prop")),
                String.class)
            .getStatusCode()
            .value());

    // Exact match is accepted.
    var matching =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(imageProfile(imagingId, 1, actual)),
            "approval",
            "t");
    assertEquals(
        200,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, matching), jsonHeaders("expected-ok")),
                JsonNode.class)
            .getStatusCode()
            .value());
  }

  // C5 (bounds): downlinkMegabytesPerSecond null/0/negative/non-finite/above-cap rejected for
  // DOWNLINK; non-null rejected for IMAGE and MANEUVER. These are OperationResourceProfile's own
  // record invariants, so a malformed profile cannot even be constructed to send over HTTP —
  // exactly the style used for SimulationPlanningContracts.Model's bound checks.
  @Test
  void downlinkRateBoundsEnforcedByConstructor() {
    var reference = new CatalogReference("catalog-x", 1);
    var expected = new ExpectedCatalogResources(10, 5, 0);

    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationResourceProfile(Operation.DOWNLINK, reference, expected, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationResourceProfile(Operation.DOWNLINK, reference, expected, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationResourceProfile(Operation.DOWNLINK, reference, expected, -1.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationResourceProfile(
                Operation.DOWNLINK, reference, expected, Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationResourceProfile(
                Operation.DOWNLINK, reference, expected, Double.POSITIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationResourceProfile(
                Operation.DOWNLINK,
                reference,
                expected,
                OperationResourceProfile.MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND + 1));
    assertDoesNotThrow(
        () -> new OperationResourceProfile(Operation.DOWNLINK, reference, expected, 5.0));
    assertDoesNotThrow(
        () ->
            new OperationResourceProfile(
                Operation.DOWNLINK,
                reference,
                expected,
                OperationResourceProfile.MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND));

    // IMAGE and MANEUVER must not declare a downlink rate.
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationResourceProfile(Operation.IMAGE, reference, expected, 5.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationResourceProfile(Operation.MANEUVER, reference, expected, 5.0));
    assertDoesNotThrow(
        () -> new OperationResourceProfile(Operation.IMAGE, reference, expected, null));
    assertDoesNotThrow(
        () -> new OperationResourceProfile(Operation.MANEUVER, reference, expected, null));
  }

  // C5 (environment): environment != "SIMULATION" rejected. Profiles' own record invariant.
  @Test
  void nonSimulationEnvironmentRejectedByConstructor() {
    var profile =
        new OperationResourceProfile(
            Operation.IMAGE,
            new CatalogReference("catalog-x", 1),
            new ExpectedCatalogResources(10, 5, 0),
            null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Profiles(
                "craft-env", "sim-v1", "HARDWARE", List.of(profile), "approval", "t"));
    assertDoesNotThrow(
        () ->
            new Profiles(
                "craft-env", "sim-v1", "SIMULATION", List.of(profile), "approval", "t"));
  }

  // Coordinator correction 1: a DOWNLINK profile with NONZERO approved generation alongside an
  // explicit drain is accepted, not rejected — ResourceTimeline already nets production and
  // drain on one Load. Both expected.generatedMegabytes and downlinkMegabytesPerSecond survive
  // the publish/read round-trip unchanged.
  @Test
  void downlinkWithNonzeroGeneratedDataAcceptedAndRoundTrips() {
    String craft = "orp-dl-nonzero-gen-" + UUID.randomUUID();
    String downlinkId = "downlink-" + craft;
    var resources = new ResourceProfile(40, 15, 0); // nonzero generatedMegabytes on a DOWNLINK op
    seedCatalog(downlinkId, 1, "DOWNLINK", resources);
    seedMission(craft);

    var profiles =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(downlinkProfile(downlinkId, 1, resources, 6.5)),
            "approval",
            "t");
    var admin = client.withBasicAuth("admin", PASSWORD);
    var response =
        admin.postForEntity(
            "/api/operation-resource-profiles",
            new HttpEntity<>(new Publish(0, profiles), jsonHeaders("dl-nonzero-gen")),
            JsonNode.class);
    assertEquals(200, response.getStatusCode().value());

    var readBack =
        client
            .withBasicAuth("service", PASSWORD)
            .getForObject("/internal/operation-resource-profiles/" + craft, JsonNode.class);
    var profile = readBack.path("body").path("profiles").get(0);
    assertEquals(15.0, profile.path("expected").path("generatedMegabytes").asDouble());
    assertEquals(6.5, profile.path("downlinkMegabytesPerSecond").asDouble());
  }

  // Coordinator correction 2: a zero-propellant MANEUVER entry is accepted, not rejected —
  // nothing in this codebase defines MANEUVER as necessarily consuming propellant.
  @Test
  void maneuverWithZeroPropellantAccepted() {
    String craft = "orp-maneuver-zero-" + UUID.randomUUID();
    String maneuverId = "maneuver-" + craft;
    var resources = new ResourceProfile(20, 0, 0);
    seedCatalog(maneuverId, 1, "MANEUVER", resources);
    seedMission(craft);

    var profiles =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(maneuverProfile(maneuverId, 1, resources)),
            "approval",
            "t");
    assertEquals(
        200,
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, profiles), jsonHeaders("maneuver-zero")),
                JsonNode.class)
            .getStatusCode()
            .value());
  }

  // C6: duplicate catalog references rejected. Profiles' own record invariant, structurally
  // detected without any persistence lookup.
  @Test
  void duplicateCatalogReferenceRejectedByConstructor() {
    var reference = new CatalogReference("catalog-dup", 1);
    var expected = new ExpectedCatalogResources(10, 5, 0);
    var first = new OperationResourceProfile(Operation.IMAGE, reference, expected, null);
    var second = new OperationResourceProfile(Operation.IMAGE, reference, expected, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Profiles(
                "craft-dup",
                "sim-v1",
                "SIMULATION",
                List.of(first, second),
                "approval",
                "t"));
  }

  // C6: a missing catalog id, or a non-existent version, is rejected (404).
  //
  // The unapproved-activity sub-case could not be exercised as a genuinely stored CatalogEntry:
  // CatalogContracts.CatalogEntry's own compact constructor already throws
  // IllegalArgumentException for `!activity.approved()`, and that constructor runs on every path
  // that can produce a CatalogEntry, including Jackson record deserialization on read. So no
  // unapproved CatalogEntry can ever reach OperationResourceApi's defensive
  // `!entry.activity().approved()` check — it is provably dead code under the current domain
  // invariant, kept only in case that invariant is ever loosened. See the comment at that check in
  // OperationResourceApi.java, and the identical situation documented in MissionCatalogBindingApi.
  @Test
  void missingCatalogReferenceRejected() {
    String craft = "orp-missing-" + UUID.randomUUID();
    String downlinkId = "downlink-" + craft;
    var resources = new ResourceProfile(30, 0, 0);
    seedMission(craft);
    var admin = client.withBasicAuth("admin", PASSWORD);

    var missingId =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(downlinkProfile("never-published-" + craft, 1, resources, 5.0)),
            "approval",
            "t");
    assertEquals(
        404,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, missingId), jsonHeaders("missing-id")),
                String.class)
            .getStatusCode()
            .value());

    seedCatalog(downlinkId, 1, "DOWNLINK", resources);
    var missingVersion =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(downlinkProfile(downlinkId, 99, resources, 5.0)),
            "approval",
            "t");
    assertEquals(
        404,
        admin
            .postForEntity(
                "/api/operation-resource-profiles",
                new HttpEntity<>(new Publish(0, missingVersion), jsonHeaders("missing-version")),
                String.class)
            .getStatusCode()
            .value());
  }

  // C7: role enforcement (OPERATOR cannot publish, requester cannot read) and idempotent replay.
  @Test
  void roleEnforcementAndIdempotentReplay() {
    String craft = "orp-role-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    var resources = new ResourceProfile(50, 10, 0);
    seedCatalog(imagingId, 1, "IMAGE", resources);
    seedMission(craft);
    var payload =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(imageProfile(imagingId, 1, resources)),
            "approval",
            "t");
    var request = new HttpEntity<>(new Publish(0, payload), jsonHeaders("role-1"));

    assertEquals(
        401,
        client
            .postForEntity("/api/operation-resource-profiles", request, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/operation-resource-profiles", request, String.class)
            .getStatusCode()
            .value());

    var admin = client.withBasicAuth("admin", PASSWORD);
    var first = admin.postForEntity("/api/operation-resource-profiles", request, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    var replay = admin.postForEntity("/api/operation-resource-profiles", request, JsonNode.class);
    assertEquals(first.getBody(), replay.getBody());
    assertEquals(
        1,
        store.list("operation-resource-profiles", 500).stream()
            .filter(s -> s.id().equals(craft))
            .findFirst()
            .orElseThrow()
            .version());

    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity("/api/operation-resource-profiles/" + craft, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        client
            .withBasicAuth("service", PASSWORD)
            .getForEntity("/internal/operation-resource-profiles/" + craft, String.class)
            .getStatusCode()
            .value());
  }

  // C8: legacy MissionProfile reads unaffected by publication. Does not itself re-run
  // CatalogApiTest or MissionCatalogBindingApiTest; see the class javadoc.
  @Test
  void legacyMissionReadIsUnaffectedByPublication() {
    String craft = "orp-legacy-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    var resources = new ResourceProfile(50, 10, 0);
    seedCatalog(imagingId, 1, "IMAGE", resources);
    seedMission(craft);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var before = admin.getForEntity("/api/missions/" + craft, JsonNode.class);
    assertEquals(200, before.getStatusCode().value());

    var payload =
        new Profiles(
            craft,
            "sim-v1",
            "SIMULATION",
            List.of(imageProfile(imagingId, 1, resources)),
            "approval",
            "t");
    admin.postForEntity(
        "/api/operation-resource-profiles",
        new HttpEntity<>(new Publish(0, payload), jsonHeaders("legacy-check")),
        JsonNode.class);

    var after = admin.getForEntity("/api/missions/" + craft, JsonNode.class);
    assertEquals(200, after.getStatusCode().value());
    assertEquals(before.getBody(), after.getBody());
  }
}
