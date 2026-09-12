package msc.services.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationProductTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final ServiceHttp http = mock(ServiceHttp.class);
  final ObjectStorage objects = mock(ObjectStorage.class);
  final UUID id = UUID.randomUUID();
  final String receiptId = "a".repeat(64);
  final byte[] bytes = {1, 2, 3, 4};
  final SimulationProductApi.Create request = new SimulationProductApi.Create(id);
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");
  StateStore store;
  JdbcTemplate db;
  SimulationProductApi api;
  JsonNode manifest, index;
  String hash;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute(
        "TRUNCATE state_head,state_history,idempotency,outbox,inbox,simulation_product_work");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1000));
    api = new SimulationProductApi(store, http, objects, json);
    hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var plan =
        json.tree(Map.of("id", receiptId, "version", 1, "body", Map.of("fixture", "pinned")));
    String planHash = json.fingerprint(plan.path("body"));
    var source =
        Map.of(
            "id",
            receiptId,
            "version",
            1,
            "body",
            Map.of(
                "environment",
                "SIMULATION",
                "status",
                "RAW_SOURCE_STORED",
                "byteCount",
                4,
                "sha256",
                hash,
                "receipt",
                Map.of("body", Map.of("planSha256", planHash))));
    var expected =
        Map.of(
            "planId",
            receiptId,
            "payloadId",
            "b".repeat(64),
            "byteCount",
            4,
            "sha256",
            hash,
            "plan",
            plan,
            "planSha256",
            planHash);
    manifest =
        json.tree(
            new StateStore.State<>(
                id.toString(),
                2,
                Map.of(
                    "environment",
                    "SIMULATION",
                    "completeness",
                    "COMPLETE",
                    "missingPlanIds",
                    List.of(),
                    "expected",
                    List.of(expected),
                    "received",
                    Map.of(receiptId, source),
                    "expectedBytes",
                    4,
                    "receivedBytes",
                    4)));
    when(http.get(eq("acquisition"), anyString(), eq(JsonNode.class))).thenReturn(manifest);
    when(http.download(eq("acquisition"), anyString(), any(), anyLong()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              ((OutputStream) call.getArgument(2)).write(bytes);
              return 4L;
            });
    when(objects.writeFile(eq("application/octet-stream"), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertArrayEquals(bytes, Files.readAllBytes((Path) call.getArgument(1)));
              return "s3://msc-product/" + hash;
            });
    when(objects.write(eq("application/json"), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              index =
                  json.read(
                      new String(
                          ((InputStream) call.getArgument(1)).readAllBytes(),
                          java.nio.charset.StandardCharsets.UTF_8),
                      JsonNode.class);
              return "s3://msc-product/" + json.fingerprint(index);
            });
  }

  @Test
  void retainsExactSourcesAndEvidenceWithoutRepeatingIO() throws Exception {
    var saved = api.create(request, "create", actor);
    var body = api.read(id).body();
    assertEquals(4, body.byteCount());
    assertEquals(hash, body.sources().getFirst().sha256());
    assertEquals(json.fingerprint(manifest), body.acquisitionManifestSha256());
    assertEquals("NOT_ASSESSED", index.path("quality").path("packetCompleteness").asText());
    assertEquals("NOT_ESTABLISHED", index.path("quality").path("sensorQualification").asText());
    clearInvocations(http, objects);
    assertEquals(json.fingerprint(saved), json.fingerprint(api.create(request, "create", actor)));
    api.create(request, "again", actor);
    verifyNoInteractions(http, objects);
    assertEquals(1, store.history("simulation-source-product", id.toString()).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void corruptDownloadDoesNotPublishProductOrIndex() throws Exception {
    doAnswer(
            call -> {
              ((OutputStream) call.getArgument(2)).write(new byte[] {4, 3, 2, 1});
              return 4L;
            })
        .when(http)
        .download(anyString(), anyString(), any(), anyLong());
    assertThrows(ApiException.class, () -> api.create(request, "bad", actor));
    verify(objects, never()).writeFile(anyString(), any());
    assertTrue(store.list("simulation-source-product", 10).isEmpty());
  }

  @Test
  void rejectsIncompleteAndInconsistentManifestBeforeObjectIO() {
    var body = (ObjectNode) manifest.path("body");
    body.put("completeness", "INCOMPLETE");
    assertThrows(ApiException.class, () -> api.create(request, "bad", actor));
    body.put("completeness", "COMPLETE");
    body.put("receivedBytes", 3);
    assertThrows(ApiException.class, () -> api.create(request, "bad", actor));
    verifyNoInteractions(objects);
    verify(http, never()).download(anyString(), anyString(), any(), anyLong());
  }

  @Test
  void failedEventRollsBackAndSameKeyRetryReusesContent() throws Exception {
    db.execute("ALTER TABLE outbox ADD CONSTRAINT reject_product CHECK (false)");
    try {
      assertThrows(RuntimeException.class, () -> api.create(request, "retry", actor));
      assertTrue(store.list("simulation-source-product", 10).isEmpty());
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_product");
    }
    var firstIndex = json.fingerprint(index);
    api.create(request, "retry", actor);
    assertEquals(firstIndex, json.fingerprint(index));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }
}
