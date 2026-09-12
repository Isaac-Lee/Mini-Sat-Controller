package msc.services.tasking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.ObservationRequest;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationResultApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new Json(new ObjectMapper().findAndRegisterModules());
  final String requestId = "request",
      scenario = "scenario",
      receipt = "a".repeat(64),
      payload = "payload";
  final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
  final byte[] content = {1, 2, 3}, preview = {4, 5};
  final String hash = sha(content), previewHash = sha(preview);
  final UsernamePasswordAuthenticationToken operator = actor("operator1", "OPERATOR"),
      owner = actor("requester", "REQUESTER");
  JdbcTemplate db;
  StateStore store;
  ServiceHttp http;
  SimulationResultApi api;
  MissionInstant now = MissionInstant.tai(1000);
  JsonNode product;
  boolean badRevision;
  String imageCommand = "image-command";

  static UsernamePasswordAuthenticationToken actor(String name, String role) {
    return new UsernamePasswordAuthenticationToken(
        name, "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  JsonNode state(String id, Object body) {
    return json.tree(new StateStore.State<>(id, 1, body));
  }

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency");
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    http = mock(ServiceHttp.class);
    api = new SimulationResultApi(store, http, json, () -> now);
    var request =
        new ObservationRequest(
            new RequestId(requestId),
            new AoiId("area"),
            1,
            "criteria",
            Optional.of(MissionInstant.tai(2000)),
            1,
            ObservationRequest.InteractionPreference.AUTO,
            ObservationRequest.Status.SCHEDULED);
    store.transaction(
        () ->
            store.create(
                "request",
                requestId,
                new RequestDetails(
                    request,
                    "requester",
                    "synthetic target",
                    Optional.empty(),
                    new Criteria(1, 1),
                    now,
                    now,
                    "scheduled",
                    List.of())));
    when(http.get(eq("spacecraft-control"), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(1);
              String load = path.contains("/image/") ? "image" : "downlink";
              if (path.endsWith("simulation-release"))
                return state(
                    load,
                    Map.of(
                        "environment",
                        "SIMULATION",
                        "scenarioId",
                        scenario,
                        "prepared",
                        Map.of(
                            "sources",
                            Map.of(
                                "schedule",
                                Map.of(
                                    "assignments",
                                    List.of(
                                        Map.of(
                                            "requestId",
                                            Map.of("value", requestId),
                                            "candidateId",
                                            Map.of("value", load),
                                            "runId",
                                            Map.of("value", load)))))),
                        "checks",
                        Map.of(
                            "decisions",
                            List.of(
                                Map.of(
                                    "body",
                                    Map.of(
                                        "requestRevision",
                                        badRevision ? 2 : 1,
                                        "candidateId",
                                        load,
                                        "runId",
                                        load))))));
              String command = load.equals("image") ? imageCommand : "downlink-command";
              return state(
                  load,
                  Map.of(
                      "loadId",
                      load,
                      "scenarioId",
                      scenario,
                      "environment",
                      "SIMULATION",
                      "status",
                      "SIMULATION_EFFECTS_CONFIRMED",
                      "requestIds",
                      List.of(requestId),
                      "ledger",
                      Map.of(
                          "body",
                          Map.of(
                              "entries",
                              List.of(
                                  Map.of(
                                      "command",
                                      Map.of("id", Map.of("value", command)),
                                      "catalog",
                                      Map.of("template", Map.of("operation", load.toUpperCase())),
                                      "status",
                                      "EFFECT_APPLIED",
                                      "completionTick",
                                      100,
                                      "catalogSha256",
                                      "b".repeat(64)))))));
            });
    var payloadSource =
        Map.of(
            "scenarioId",
            scenario,
            "commandId",
            "image-command",
            "completionTick",
            100,
            "catalogSha256",
            "b".repeat(64));
    var plan =
        state(
            receipt,
            Map.of(
                "request",
                Map.of(
                    "scenarioId",
                    scenario,
                    "loadId",
                    "downlink",
                    "commandId",
                    "downlink-command",
                    "payloadId",
                    payload),
                "payload",
                Map.of("source", payloadSource, "sha256", hash, "byteCount", 3)));
    var manifest =
        state(
            productId.toString(),
            Map.of(
                "scenarioId",
                scenario,
                "environment",
                "SIMULATION",
                "completeness",
                "COMPLETE",
                "missingPlanIds",
                List.of(),
                "expectedBytes",
                3,
                "receivedBytes",
                3,
                "expected",
                List.of(
                    Map.of(
                        "planId",
                        receipt,
                        "plan",
                        plan,
                        "payloadId",
                        payload,
                        "sha256",
                        hash,
                        "byteCount",
                        3))));
    product =
        state(
            productId.toString(),
            Map.of(
                "environment",
                "SIMULATION",
                "status",
                "SOURCE_PACKAGE_STORED",
                "format",
                "MSC_SIMULATED_SOURCE_PACKAGE_V1",
                "acquisitionManifest",
                manifest,
                "acquisitionManifestSha256",
                json.fingerprint(manifest),
                "byteCount",
                3,
                "sources",
                List.of(
                    Map.of(
                        "receiptId",
                        receipt,
                        "payloadId",
                        payload,
                        "sha256",
                        hash,
                        "byteCount",
                        3))));
    when(http.get(eq("product"), anyString(), eq(JsonNode.class)))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(1);
              if (path.endsWith("/preview"))
                return state(
                    "preview",
                    Map.of(
                        "productId",
                        productId.toString(),
                        "receiptId",
                        receipt,
                        "environment",
                        "SIMULATION",
                        "sourceSha256",
                        hash,
                        "sourceByteCount",
                        3,
                        "sha256",
                        previewHash,
                        "byteCount",
                        2));
              return product;
            });
    when(http.download(eq("product"), anyString(), any(), anyLong()))
        .thenAnswer(
            inv -> {
              String path = inv.getArgument(1);
              java.io.OutputStream output = inv.getArgument(2);
              byte[] bytes = path.contains("/preview/") ? preview : content;
              output.write(bytes);
              return (long) bytes.length;
            });
  }

  SimulationResultApi.Complete command() {
    return new SimulationResultApi.Complete(
        1, productId, "image", "downlink", "V1 operator review");
  }

  @Test
  void completesSimulationAndOwnerCanReadVerifiedContent() throws Exception {
    var result = api.complete(requestId, command(), "complete", operator);
    assertEquals("SIMULATION", result.path("body").path("environment").asText());
    assertEquals(
        ObservationRequest.Status.FULFILLED,
        store.require("request", requestId, RequestDetails.class).body().request().status());
    assertTrue(
        store
            .require("request", requestId, RequestDetails.class)
            .body()
            .reason()
            .startsWith("SIMULATION_V1_COMPLETE"));
    assertEquals(
        json.fingerprint(result),
        json.fingerprint(api.complete(requestId, command(), "complete", operator)));
    assertEquals(productId.toString(), api.read(requestId, owner).body().productId());
    var bytes = new java.io.ByteArrayOutputStream();
    api.raw(requestId, receipt, owner).getBody().writeTo(bytes);
    assertArrayEquals(content, bytes.toByteArray());
    bytes.reset();
    api.preview(requestId, receipt, owner).getBody().writeTo(bytes);
    assertArrayEquals(preview, bytes.toByteArray());
    assertThrows(ApiException.class, () -> api.read(requestId, actor("someone-else", "REQUESTER")));
    assertThrows(ApiException.class, () -> api.raw(requestId, "other", owner));
  }

  @Test
  void wrongCommandAndOldRequestRevisionCannotFulfill() {
    imageCommand = "different-image";
    assertThrows(
        ApiException.class, () -> api.complete(requestId, command(), "wrong-command", operator));
    imageCommand = "image-command";
    badRevision = true;
    assertThrows(
        ApiException.class, () -> api.complete(requestId, command(), "wrong-revision", operator));
    assertTrue(
        store
            .find("request-simulation-result", requestId, SimulationResultApi.Result.class)
            .isEmpty());
    assertEquals(
        ObservationRequest.Status.SCHEDULED,
        store.require("request", requestId, RequestDetails.class).body().request().status());
  }

  @Test
  void eventFailureRollsBackCompletionAndSameKeyCanRetry() {
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_completion CHECK (event_type <>"
            + " 'SimulationRequestCompleted')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> api.complete(requestId, command(), "retry", operator));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_completion");
    }
    assertTrue(
        store
            .find("request-simulation-result", requestId, SimulationResultApi.Result.class)
            .isEmpty());
    assertEquals(1, store.require("request", requestId, RequestDetails.class).version());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    assertNotNull(api.complete(requestId, command(), "retry", operator).path("body"));
  }

  @Test
  void expiredRequestAndCorruptDownloadAreRejected() throws Exception {
    now = MissionInstant.tai(2001);
    assertThrows(ApiException.class, () -> api.complete(requestId, command(), "expired", operator));
    now = MissionInstant.tai(1000);
    api.complete(requestId, command(), "valid", operator);
    doAnswer(
            inv -> {
              java.io.OutputStream output = inv.getArgument(2);
              output.write(new byte[] {9, 9, 9});
              return 3L;
            })
        .when(http)
        .download(eq("product"), anyString(), any(), anyLong());
    assertThrows(
        java.io.IOException.class,
        () ->
            api.raw(requestId, receipt, owner)
                .getBody()
                .writeTo(new java.io.ByteArrayOutputStream()));
  }
}
