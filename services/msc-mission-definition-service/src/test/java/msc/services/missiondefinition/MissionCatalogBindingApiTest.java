package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.*;
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
 * Owner tests for {@link MissionCatalogBindingApi}, the new multi-activity catalog binding API.
 * This file exercises only the new API and its new contracts; it does not modify or re-run {@code
 * CatalogApiTest} (B6's "existing CatalogApiTest still passes unchanged" therefore still requires
 * that test class to be run separately — see the handoff for what this agent could and could not
 * run itself). {@code legacyMissionProfileReadIsUnaffectedByBindingsPublication} below is a
 * same-module demonstration that publishing bindings does not disturb the legacy single-catalog
 * {@code MissionProfile} read path, not a substitute for running {@code CatalogApiTest}.
 */
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MissionCatalogBindingApiTest {
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

  private static CatalogEntry catalogEntry(String activityId, long version, String operation) {
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
    var resources = new ResourceProfile(10, 1, 0);
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

  private void seedCatalog(String activityId, long version, String operation) {
    var entry = catalogEntry(activityId, version, operation);
    store.transaction(() -> store.create("catalog", activityId + ":" + version, entry));
  }

  /** Seeds a mission whose legacy single-catalog pin is {@code (imagingCatalogId, version 1)}. */
  private void seedMission(String craft, String imagingCatalogId) {
    store.transaction(
        () ->
            store.create(
                "mission",
                craft,
                new MissionProfile(
                    craft,
                    imagingCatalogId,
                    1,
                    "sim-v1",
                    100,
                    20,
                    1000,
                    1,
                    10,
                    "sim-time",
                    "synthetic-test-fixture")));
  }

  private static HttpHeaders jsonHeaders(String idempotencyKey) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  private static RoleBinding imaging(String catalogId) {
    return new RoleBinding(Role.IMAGING, new CatalogReference(catalogId, 1));
  }

  private static RoleBinding downlink(String catalogId) {
    return new RoleBinding(Role.DOWNLINK, new CatalogReference(catalogId, 1));
  }

  private static RoleBinding maneuver(String catalogId) {
    return new RoleBinding(Role.MANEUVER, new CatalogReference(catalogId, 1));
  }

  // B1: publish/read-back/CAS — stale expectedVersion 409, correct one yields version 2, version
  // 1 re-reads byte-identical.
  @Test
  void publishReadBackAndCasEnforceExactHistory() {
    String craft = "mcb-cas-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    String maneuverId = "maneuver-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedCatalog(maneuverId, 1, "MANEUVER");
    seedMission(craft, imagingId);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var v1 = new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(downlinkId)), "t");
    var first =
        admin.postForEntity(
            "/api/mission-catalog-bindings",
            new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-1")),
            JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(1, first.getBody().path("version").asInt());

    // Stale expectedVersion (0) after the head has already moved to version 1.
    assertEquals(
        409,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, v1), jsonHeaders("cas-stale")),
                String.class)
            .getStatusCode()
            .value());

    var v2 =
        new Bindings(
            craft,
            "sim-v1",
            List.of(imaging(imagingId), downlink(downlinkId), maneuver(maneuverId)),
            "t");
    var second =
        admin.postForEntity(
            "/api/mission-catalog-bindings",
            new HttpEntity<>(new Publish(1, v2), jsonHeaders("cas-2")),
            JsonNode.class);
    assertEquals(200, second.getStatusCode().value());
    assertEquals(2, second.getBody().path("version").asInt());

    var service = client.withBasicAuth("service", PASSWORD);
    assertEquals(
        first.getBody(),
        service.getForObject(
            "/internal/mission-catalog-bindings/" + craft + "/versions/1", JsonNode.class));
    assertEquals(
        second.getBody(),
        service.getForObject("/internal/mission-catalog-bindings/" + craft, JsonNode.class));
  }

  // B2: mission-definition-version mismatch rejected.
  @Test
  void missionDefinitionVersionMismatchRejected() {
    String craft = "mcb-mdv-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var mismatched =
        new Bindings(
            craft,
            "not-the-configured-mission-version",
            List.of(imaging(imagingId), downlink(downlinkId)),
            "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("mdv-1")),
                String.class)
            .getStatusCode()
            .value());
  }

  // B3: a role referencing a missing catalog id, or a non-existent version, is rejected.
  //
  // The other half of B3 ("an unapproved activity is rejected") could not be exercised as a
  // genuinely stored CatalogEntry: CatalogContracts.CatalogEntry's own compact constructor
  // already throws IllegalArgumentException for `!activity.approved()`, and that constructor runs
  // on every path that can produce a CatalogEntry, including Jackson record deserialization on
  // read. So no unapproved CatalogEntry can ever reach MissionCatalogBindingApi's defensive
  // `!entry.activity().approved()` check — it is provably dead code under the current domain
  // invariant, kept only in case that invariant is ever loosened. See the class javadoc comment
  // above that check in MissionCatalogBindingApi.java.
  @Test
  void missingCatalogReferenceRejected() {
    String craft = "mcb-missing-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedMission(craft, imagingId);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var missingId =
        new Bindings(
            craft,
            "sim-v1",
            List.of(imaging(imagingId), downlink("never-published-" + craft)),
            "t");
    assertEquals(
        404,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, missingId), jsonHeaders("missing-id")),
                String.class)
            .getStatusCode()
            .value());

    seedCatalog(downlinkId, 1, "DOWNLINK");
    var downlinkMissingVersion =
        new RoleBinding(Role.DOWNLINK, new CatalogReference(downlinkId, 99));
    var missingVersion =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlinkMissingVersion), "t");
    assertEquals(
        404,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, missingVersion), jsonHeaders("missing-version")),
                String.class)
            .getStatusCode()
            .value());
  }

  // B4: duplicate roles rejected; a binding missing IMAGING or DOWNLINK rejected; MANEUVER
  // optional and accepted when absent. Duplicate-role and missing-required-role rejection are
  // Bindings' own record invariants (same style as AgilityContracts.Model's assertThrows checks
  // in CatalogApiTest), so a Bindings with those shapes cannot even be constructed to send over
  // HTTP.
  @Test
  void duplicateAndMissingRequiredRolesRejectedManeuverIsOptional() {
    String craft = "mcb-roles-" + UUID.randomUUID();
    var imagingBinding = imaging("imaging-" + craft);
    var duplicateImagingBinding = imaging("imaging-2-" + craft);
    var downlinkBinding = downlink("downlink-" + craft);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Bindings(
                craft,
                "sim-v1",
                List.of(imagingBinding, duplicateImagingBinding, downlinkBinding),
                "t"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Bindings(craft, "sim-v1", List.of(imagingBinding), "t"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Bindings(craft, "sim-v1", List.of(downlinkBinding), "t"));

    String imagingId = "imaging-only-" + craft;
    String downlinkId = "downlink-only-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId);
    var noManeuver =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(downlinkId)), "t");
    var response =
        client
            .withBasicAuth("admin", PASSWORD)
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, noManeuver), jsonHeaders("no-maneuver")),
                JsonNode.class);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(2, response.getBody().path("body").path("roles").size());
  }

  // B5: role enforcement (OPERATOR cannot publish; requester cannot read) and idempotent replay.
  @Test
  void roleEnforcementAndIdempotentReplay() {
    String craft = "mcb-role-enforce-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId);
    var payload =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(downlinkId)), "t");
    var request = new HttpEntity<>(new Publish(0, payload), jsonHeaders("role-enforce-1"));

    assertEquals(
        401,
        client
            .postForEntity("/api/mission-catalog-bindings", request, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        client
            .withBasicAuth("operator1", PASSWORD)
            .postForEntity("/api/mission-catalog-bindings", request, String.class)
            .getStatusCode()
            .value());

    var admin = client.withBasicAuth("admin", PASSWORD);
    var first = admin.postForEntity("/api/mission-catalog-bindings", request, JsonNode.class);
    assertEquals(200, first.getStatusCode().value());
    var replay = admin.postForEntity("/api/mission-catalog-bindings", request, JsonNode.class);
    assertEquals(first.getBody(), replay.getBody());

    assertEquals(
        403,
        client
            .withBasicAuth("requester", PASSWORD)
            .getForEntity("/api/mission-catalog-bindings/" + craft, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        client
            .withBasicAuth("service", PASSWORD)
            .getForEntity("/internal/mission-catalog-bindings/" + craft, String.class)
            .getStatusCode()
            .value());
  }

  // Supports B6: publishing new bindings must not disturb the legacy single-catalog
  // MissionProfile read path. This does not itself re-run CatalogApiTest; see the class javadoc.
  @Test
  void legacyMissionProfileReadIsUnaffectedByBindingsPublication() {
    String craft = "mcb-legacy-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var before = admin.getForEntity("/api/missions/" + craft, JsonNode.class);
    assertEquals(200, before.getStatusCode().value());
    assertEquals(imagingId, before.getBody().get("catalogId").asText());
    assertEquals(1, before.getBody().get("catalogVersion").asInt());

    var payload =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(downlinkId)), "t");
    admin.postForEntity(
        "/api/mission-catalog-bindings",
        new HttpEntity<>(new Publish(0, payload), jsonHeaders("legacy-check")),
        JsonNode.class);

    var after = admin.getForEntity("/api/missions/" + craft, JsonNode.class);
    assertEquals(200, after.getStatusCode().value());
    assertEquals(before.getBody(), after.getBody());
  }

  // B8 (Codex correction): a catalog entry whose template operation does not match the role's
  // required operation is rejected, so a role cannot be satisfied merely by being assigned that
  // map key regardless of what the entry actually does.
  @Test
  void roleOperationMismatchRejected() {
    String craft = "mcb-op-mismatch-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String wrongOpId = "wrong-op-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(wrongOpId, 1, "IMAGE"); // declares IMAGE, will be bound to DOWNLINK below
    seedMission(craft, imagingId);

    var admin = client.withBasicAuth("admin", PASSWORD);
    var mismatched =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(wrongOpId)), "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, mismatched), jsonHeaders("op-mismatch")),
                String.class)
            .getStatusCode()
            .value());
  }

  // Codex correction 2: the IMAGING role must equal the spacecraft's legacy MissionProfile pin
  // exactly, so there is never a second, divergent source of truth for the imaging activity.
  @Test
  void imagingRoleMustMatchLegacyCatalogPinExactly() {
    String craft = "mcb-precedence-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String otherImagingId = "other-imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE");
    seedCatalog(otherImagingId, 1, "IMAGE");
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId); // legacy pin is imagingId:1

    var admin = client.withBasicAuth("admin", PASSWORD);
    var divergent =
        new Bindings(craft, "sim-v1", List.of(imaging(otherImagingId), downlink(downlinkId)), "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, divergent), jsonHeaders("precedence-mismatch")),
                String.class)
            .getStatusCode()
            .value());

    var consistent =
        new Bindings(craft, "sim-v1", List.of(imaging(imagingId), downlink(downlinkId)), "t");
    assertEquals(
        200,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, consistent), jsonHeaders("precedence-match")),
                JsonNode.class)
            .getStatusCode()
            .value());
  }

  // Opus correction 1: pins the documented consequence of the strict-equality precedence rule.
  // POST /api/missions (CatalogApi.java:56-78) only ever store.create()s a mission once; there is
  // no CAS/update route for MissionProfile anywhere in this service, so its
  // catalogId/catalogVersion pin is immutable for the life of a spacecraft record. Combined with
  // the strict-equality rule above, binding IMAGING to *any* other version of the very same
  // activity — including a newer, independently valid, approved catalog entry — is unreachable
  // through this API today. This test pins that as current, intended behaviour (not a latent bug)
  // so a future change either to this rule or to a mission-profile update path is a deliberate
  // decision, not a silent regression.
  @Test
  void imagingRoleCannotRotateToNewerApprovedVersionOfSameActivity() {
    String craft = "mcb-frozen-pin-" + UUID.randomUUID();
    String imagingId = "imaging-" + craft;
    String downlinkId = "downlink-" + craft;
    seedCatalog(imagingId, 1, "IMAGE"); // the legacy pin, version 1
    seedCatalog(imagingId, 2, "IMAGE"); // a newer, independently valid, approved version
    seedCatalog(downlinkId, 1, "DOWNLINK");
    seedMission(craft, imagingId); // legacy pin is imagingId:1; MissionProfile is create-only

    var admin = client.withBasicAuth("admin", PASSWORD);
    var rotated =
        new Bindings(
            craft,
            "sim-v1",
            List.of(
                new RoleBinding(Role.IMAGING, new CatalogReference(imagingId, 2)),
                downlink(downlinkId)),
            "t");
    assertEquals(
        400,
        admin
            .postForEntity(
                "/api/mission-catalog-bindings",
                new HttpEntity<>(new Publish(0, rotated), jsonHeaders("frozen-pin-rotate")),
                String.class)
            .getStatusCode()
            .value());
  }
}
