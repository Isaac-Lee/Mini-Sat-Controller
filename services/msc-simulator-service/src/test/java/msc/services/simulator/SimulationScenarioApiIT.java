package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.*;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.time.*;
import msc.platform.*;
import msc.services.simulator.SimulationScenarioApi.*;
import msc.services.simulator.SimulatorOperationEffects.Reservoirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulationScenarioApiIT {
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
  @Autowired StateStore store;
  @Autowired Json json;
  @Autowired JdbcTemplate db;
  @MockitoBean ServiceHttp owner;

  Create fixture() {
    String craft = "sim-" + UUID.randomUUID();
    var mission = new MissionProfile(craft, "image", 1, "m1", 100, 10, 100, 10, 0, "c1", "test");
    var correlation =
        new Correlation(
            craft,
            "m1",
            "c1",
            "p1",
            MissionInstant.tai(1000),
            0,
            10,
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
            "SIMULATION",
            "test",
            "test");
    var profiles =
        new Profiles(
            craft,
            "m1",
            "SIMULATION",
            List.of(
                new OperationResourceProfile(
                    Operation.IMAGE,
                    new CatalogReference("image", 1),
                    new ExpectedCatalogResources(10, 1, 0),
                    null)),
            "test",
            "test");
    when(owner.get("mission-definition", "/internal/missions/" + craft, MissionProfile.class))
        .thenReturn(mission);
    when(owner.get(
            "mission-definition",
            "/internal/simulation-time-correlations/" + craft + "/versions/2",
            JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>(craft, 2, correlation)));
    when(owner.get(
            "mission-definition",
            "/internal/operation-resource-profiles/" + craft + "/versions/3",
            JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>(craft, 3, profiles)));
    return new Create(UUID.randomUUID(), craft, 2, 3, 10, new Reservoirs(20, 5), "test");
  }

  ResponseEntity<JsonNode> post(Create request, String key, String actor) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return client
        .withBasicAuth(actor, PASSWORD)
        .postForEntity(
            "/api/simulation/scenarios", new HttpEntity<>(request, headers), JsonNode.class);
  }

  @Test
  void durableReplayDoesNotRefetchOwnerAndPreservesOneHistoryAndEvent() {
    var request = fixture();
    String key = UUID.randomUUID().toString();
    var response = post(request, key, "admin");
    assertEquals(200, response.getStatusCode().value());
    var scenario = json.convert(response.getBody().get("body"), Scenario.class);
    assertEquals(2, scenario.correlation().version());
    assertEquals(3, scenario.resourceProfiles().version());
    assertEquals(json.fingerprint(scenario.mission()), scenario.missionSha256());
    assertEquals(json.fingerprint(scenario.correlation()), scenario.correlationSha256());
    assertEquals(json.fingerprint(scenario.resourceProfiles()), scenario.resourceProfilesSha256());
    reset(owner);
    assertEquals(response.getBody(), post(request, key, "admin").getBody());
    assertEquals(409, post(request, key + "-different", "admin").getStatusCode().value());
    verifyNoInteractions(owner);
    // A new controller instance reads the database, not any original in-memory scenario object.
    var restored = new SimulationScenarioApi(store, owner, json).read(request.id());
    assertEquals(scenario, restored.body());
    assertEquals(1, store.history("simulation-scenario", request.id().toString()).size());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE envelope->>'aggregateId'=? AND"
                + " event_type='SimulationScenarioCreated'",
            Integer.class,
            request.id().toString()));
    var changed =
        new Create(request.id(), request.spacecraftId(), 2, 3, 11, request.reservoirs(), "test");
    assertEquals(409, post(changed, key, "admin").getStatusCode().value());
    verifyNoInteractions(owner);
  }

  @Test
  void concurrentCreationReturnsOnePersistedScenario() throws Exception {
    var request = fixture();
    String key = UUID.randomUUID().toString();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return post(request, key, "admin");
                }));
      start.countDown();
      JsonNode original = null;
      for (var future : futures) {
        var result = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, result.getStatusCode().value());
        if (original == null) original = result.getBody();
        else assertEquals(original, result.getBody());
      }
    }
    assertEquals(1, store.history("simulation-scenario", request.id().toString()).size());
  }

  @Test
  void publicationAndDiagnosticReadRolesAreEnforced() {
    var request = fixture();
    for (String actor : List.of("service", "operator1", "requester"))
      assertEquals(403, post(request, "denied", actor).getStatusCode().value());
    assertEquals(200, post(request, "create", "admin").getStatusCode().value());
    for (String actor : List.of("admin", "service"))
      assertEquals(
          200,
          client
              .withBasicAuth(actor, PASSWORD)
              .getForEntity(
                  (actor.equals("admin") ? "/api" : "/internal")
                      + "/simulation/scenarios/"
                      + request.id(),
                  JsonNode.class)
              .getStatusCode()
              .value());
    for (String actor : List.of("operator1", "requester"))
      assertEquals(
          403,
          client
              .withBasicAuth(actor, PASSWORD)
              .getForEntity("/api/simulation/scenarios/" + request.id(), JsonNode.class)
              .getStatusCode()
              .value());
  }

  @Test
  void ownerVersionMismatchAndInvalidInitialStatePersistNothing() {
    var request = fixture();
    var wrong = json.tree(Map.of("id", request.spacecraftId(), "version", 99, "body", Map.of()));
    when(owner.get(
            "mission-definition",
            "/internal/simulation-time-correlations/" + request.spacecraftId() + "/versions/2",
            JsonNode.class))
        .thenReturn(wrong);
    assertEquals(400, post(request, "bad-version", "admin").getStatusCode().value());
    assertTrue(
        store.find("simulation-scenario", request.id().toString(), Scenario.class).isEmpty());
    var good = fixture();
    for (var bad :
        List.of(
            new Create(good.id(), good.spacecraftId(), 2, 3, 10000, good.reservoirs(), "test"),
            new Create(good.id(), good.spacecraftId(), 2, 3, 10, new Reservoirs(101, 5), "test"))) {
      assertEquals(400, post(bad, UUID.randomUUID().toString(), "admin").getStatusCode().value());
      assertTrue(store.find("simulation-scenario", good.id().toString(), Scenario.class).isEmpty());
    }
  }

  private SimulationCommandApi.Submit command(Create scenario, long tick, double duration) {
    var catalog =
        new msc.contracts.CatalogContracts.CatalogEntry(
            "image",
            1,
            new msc.domain.missiondefinition.ActivityDefinition(
                new msc.domain.shared.Ids.ActivityDefinitionId("image"),
                1,
                "image",
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                msc.domain.missiondefinition.AuthorityPolicy.RiskClass.LOW,
                "template:1"),
            new msc.contracts.CatalogContracts.CommandTemplate("template", 1, "IMAGE", Map.of()),
            new msc.contracts.CatalogContracts.ResourceProfile(10, 1, 0),
            msc.domain.missiondefinition.AuthorityPolicy.Requirement.HUMAN_APPROVAL,
            duration,
            "synthetic");
    when(owner.get(
            "mission-definition",
            "/internal/catalog/image/versions/1",
            msc.contracts.CatalogContracts.CatalogEntry.class))
        .thenReturn(catalog);
    var command =
        new msc.domain.spacecraftcontrol.CommandInstance(
            new msc.domain.shared.Ids.CommandId(UUID.randomUUID().toString()),
            "template:1",
            Map.of(),
            new OnboardTime(tick, "p1", new msc.domain.shared.Ids.TimeCorrelationId("c1")));
    var load =
        new msc.domain.spacecraftcontrol.CommandLoad(
            new msc.domain.shared.Ids.CommandLoadId(UUID.randomUUID().toString()),
            new msc.domain.planning.ScheduleKey(
                new msc.domain.shared.Ids.SpacecraftId(scenario.spacecraftId()),
                new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000))),
            1,
            List.of(command),
            "m1",
            new msc.domain.shared.Ids.TimeCorrelationId("c1"),
            "synthetic-artifact-checksum",
            Optional.of("trace-only-not-release-authority"),
            MissionInstant.tai(1010));
    return new SimulationCommandApi.Submit(
        load, Map.of(command.id().value(), new CatalogReference("image", 1)));
  }

  private ResponseEntity<JsonNode> commandPost(
      UUID scenario, String suffix, Object body, String key, String actor) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    String prefix = suffix.equals("advance") ? "/api" : "/internal";
    return client
        .withBasicAuth(actor, PASSWORD)
        .postForEntity(
            prefix + "/simulation/scenarios/" + scenario + "/" + suffix,
            new HttpEntity<>(body, headers),
            JsonNode.class);
  }

  @Test
  void commandCompletesOnceAndOriginalReceiptSurvivesClockChanges() {
    var request = fixture();
    assertEquals(200, post(request, UUID.randomUUID().toString(), "admin").getStatusCode().value());
    var load = command(request, 20, 10);
    String key = UUID.randomUUID().toString();
    var receipt = commandPost(request.id(), "loads", load, key, "service");
    assertEquals(200, receipt.getStatusCode().value());
    assertEquals("PENDING", receipt.getBody().at("/body/entries/0/status").asText());
    var beforeEnd =
        commandPost(
            request.id(), "advance", new SimulationCommandApi.Advance(1, 119), key, "admin");
    assertEquals(200, beforeEnd.getStatusCode().value());
    assertEquals(20, beforeEnd.getBody().at("/body/reservoirs/storedMegabytes").asDouble());
    var atEnd = new SimulationCommandApi.Advance(2, 120);
    var applied = commandPost(request.id(), "advance", atEnd, key + "-end", "admin");
    assertEquals(200, applied.getStatusCode().value());
    assertEquals(21, applied.getBody().at("/body/reservoirs/storedMegabytes").asDouble());
    assertEquals(
        applied.getBody(),
        commandPost(request.id(), "advance", atEnd, key + "-end", "admin").getBody());
    reset(owner);
    assertEquals(
        receipt.getBody(),
        commandPost(request.id(), "loads", load, key + "-retransmit", "service").getBody());
    var restarted = new SimulationCommandApi(store, owner, json);
    assertTrue(
        store
            .replay("simulation-load:" + request.id() + ":service", key + "-retransmit", load)
            .isPresent());
    var ledger = restarted.read(request.id(), load.load().id().value());
    assertEquals(
        SimulationCommandApi.Status.EFFECT_APPLIED, ledger.body().entries().getFirst().status());
    assertEquals(20, ledger.body().entries().getFirst().effect().before().storedMegabytes());
    assertEquals(21, ledger.body().entries().getFirst().effect().after().storedMegabytes());
    var later =
        commandPost(
            request.id(),
            "advance",
            new SimulationCommandApi.Advance(3, 130),
            key + "-later",
            "admin");
    assertEquals(21, later.getBody().at("/body/reservoirs/storedMegabytes").asDouble());
    verifyNoInteractions(owner);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='SpacecraftExecutionObserved'",
            Integer.class));
  }

  @Test
  void rejectsOverlapOffGridDurationAndChangedLoadWithoutEffect() {
    var scenario = fixture();
    assertEquals(
        200, post(scenario, UUID.randomUUID().toString(), "admin").getStatusCode().value());
    var first = command(scenario, 20, 10);
    assertEquals(
        200,
        commandPost(scenario.id(), "loads", first, "load-1", "service").getStatusCode().value());
    var overlap = command(scenario, 119, 10);
    assertEquals(
        409,
        commandPost(scenario.id(), "loads", overlap, "overlap", "service").getStatusCode().value());
    var offGrid = command(scenario, 200, 0.15);
    assertEquals(
        400,
        commandPost(scenario.id(), "loads", offGrid, "off-grid", "service")
            .getStatusCode()
            .value());
    var changed =
        new SimulationCommandApi.Submit(
            first.load(),
            Map.of(
                first.load().commands().getFirst().id().value(), new CatalogReference("other", 1)));
    assertEquals(
        409,
        commandPost(scenario.id(), "loads", changed, "different", "service")
            .getStatusCode()
            .value());
    assertEquals(
        400,
        commandPost(
                scenario.id(),
                "advance",
                new SimulationCommandApi.Advance(1, 10000),
                "outside",
                "admin")
            .getStatusCode()
            .value());
    assertEquals(
        403,
        commandPost(
                scenario.id(),
                "advance",
                new SimulationCommandApi.Advance(1, 120),
                "forbidden",
                "service")
            .getStatusCode()
            .value());
    assertEquals(
        400,
        commandPost(
                scenario.id(),
                "advance",
                new SimulationCommandApi.Advance(1, Long.MAX_VALUE),
                "overflow",
                "admin")
            .getStatusCode()
            .value());
    assertEquals(1, store.history("simulation-scenario", scenario.id().toString()).size());
  }

  @Test
  void scenarioWriteFailureRollsBackLedgerAndIdempotency() {
    var scenario = fixture();
    assertEquals(
        200, post(scenario, UUID.randomUUID().toString(), "admin").getStatusCode().value());
    var load = command(scenario, 20, 10);
    assertEquals(
        200,
        commandPost(scenario.id(), "loads", load, "submit", "service").getStatusCode().value());
    var failing = spy(store);
    doThrow(new IllegalStateException("injected scenario write failure"))
        .when(failing)
        .update(eq("simulation-scenario"), eq(scenario.id().toString()), eq(1L), any());
    var restarted = new SimulationCommandApi(failing, owner, json);
    var actor =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "admin", "unused");
    assertThrows(
        IllegalStateException.class,
        () ->
            restarted.advance(
                scenario.id(), new SimulationCommandApi.Advance(1, 120), "rollback", actor));
    assertEquals(
        1, store.history("simulation-load:" + scenario.id(), load.load().id().value()).size());
    assertEquals(1, store.history("simulation-scenario", scenario.id().toString()).size());
    assertEquals(
        200,
        commandPost(
                scenario.id(),
                "advance",
                new SimulationCommandApi.Advance(1, 120),
                "rollback",
                "admin")
            .getStatusCode()
            .value());
    assertEquals(
        21,
        store
            .require("simulation-scenario", scenario.id().toString(), Scenario.class)
            .body()
            .reservoirs()
            .storedMegabytes());
  }
}
