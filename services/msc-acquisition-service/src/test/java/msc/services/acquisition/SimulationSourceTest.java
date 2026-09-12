package msc.services.acquisition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
class SimulationSourceTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final ServiceHttp http = mock(ServiceHttp.class);
  final ObjectStorage objects = mock(ObjectStorage.class);
  final String id = "a".repeat(64);
  final SimulationSourceApi.Import request = new SimulationSourceApi.Import(id);
  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");
  final byte[] bytes = new byte[] {1, 2, 3, 4};
  StateStore store;
  JdbcTemplate db;
  SimulationSourceApi api;
  JsonNode receipt;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,idempotency,outbox");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1000));
    api = new SimulationSourceApi(store, http, objects, json);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    receipt =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .valueToTree(
                new StateStore.State<>(
                    id,
                    1,
                    Map.of(
                        "planId",
                        id,
                        "planSha256",
                        "b".repeat(64),
                        "stationId",
                        "station",
                        "ledgerVersion",
                        3,
                        "linkVersion",
                        2,
                        "byteCount",
                        bytes.length,
                        "sha256",
                        hash,
                        "receivedAt",
                        MissionInstant.tai(1000),
                        "environment",
                        "SIMULATION")));
    when(http.get(eq("simulator"), anyString(), eq(JsonNode.class))).thenReturn(receipt);
    when(http.download(eq("simulator"), anyString(), any(), anyLong()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              ((OutputStream) call.getArgument(2)).write(bytes);
              return (long) bytes.length;
            });
    when(objects.writeFile(eq("application/octet-stream"), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertArrayEquals(bytes, Files.readAllBytes((Path) call.getArgument(1)));
              return "s3://msc-acquisition/" + hash;
            });
  }

  @Test
  void storesExactSourceAndReplaysWithoutOwnerOrObjectIO() throws Exception {
    var first = api.acquire(request, "first", actor);
    assertEquals("RAW_SOURCE_STORED", api.read(id).body().status());
    assertEquals(json.fingerprint(receipt), api.read(id).body().receiptSha256());
    assertEquals(1, store.history("simulation-acquisition-source", id).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    clearInvocations(http, objects);
    assertEquals(json.fingerprint(first), json.fingerprint(api.acquire(request, "first", actor)));
    assertEquals(json.fingerprint(first), json.fingerprint(api.acquire(request, "second", actor)));
    verifyNoInteractions(http, objects);
  }

  @Test
  void rejectsCorruptAndTruncatedBytesBeforeStorage() throws Exception {
    doAnswer(
            call -> {
              ((OutputStream) call.getArgument(2)).write(new byte[] {4, 3, 2, 1});
              return 4L;
            })
        .when(http)
        .download(eq("simulator"), anyString(), any(), anyLong());
    assertThrows(ApiException.class, () -> api.acquire(request, "bad", actor));
    doAnswer(
            call -> {
              ((OutputStream) call.getArgument(2)).write(new byte[] {1, 2, 3});
              return 3L;
            })
        .when(http)
        .download(eq("simulator"), anyString(), any(), anyLong());
    assertThrows(ApiException.class, () -> api.acquire(request, "short", actor));
    verifyNoInteractions(objects);
    assertTrue(store.list("simulation-acquisition-source", 10).isEmpty());
  }

  @Test
  void failedEventRollsBackMetadataAndAllowsSameKeyRetry() throws Exception {
    db.execute("ALTER TABLE outbox ADD CONSTRAINT reject_source CHECK (false)");
    try {
      assertThrows(RuntimeException.class, () -> api.acquire(request, "retry", actor));
      assertTrue(store.list("simulation-acquisition-source", 10).isEmpty());
      assertEquals(0, db.queryForObject("SELECT count(*) FROM idempotency", Integer.class));
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_source");
    }
    api.acquire(request, "retry", actor);
    assertEquals(1, store.history("simulation-acquisition-source", id).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void rejectsMissingOrNonpositiveEvidenceVersions() {
    for (String field : new String[] {"ledgerVersion", "linkVersion"}) {
      var body = (com.fasterxml.jackson.databind.node.ObjectNode) receipt.path("body");
      var original = body.get(field);
      for (long invalid : new long[] {0, -1}) {
        body.put(field, invalid);
        assertThrows(ApiException.class, () -> api.validate(request, receipt));
      }
      body.remove(field);
      assertThrows(ApiException.class, () -> api.validate(request, receipt));
      body.set(field, original);
    }
    verifyNoInteractions(objects);
  }

  @Test
  void rejectsUnboundReceiptBeforeDownloading() {
    ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.path("body"))
        .put("planId", "c".repeat(64));
    assertThrows(ApiException.class, () -> api.acquire(request, "wrong", actor));
    verify(http, never()).download(anyString(), anyString(), any(), anyLong());
    verifyNoInteractions(objects);
  }
}
