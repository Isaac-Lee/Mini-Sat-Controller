package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationPayloadTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final ObjectStorage objects = mock(ObjectStorage.class);
  StateStore store;
  JdbcTemplate db;
  SimulationPayloadApi api;
  byte[] content;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,simulation_payload_work");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1000));
    api = new SimulationPayloadApi(store, json, db, objects);
    when(objects.write(anyString(), any()))
        .thenAnswer(
            call -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              content = ((InputStream) call.getArgument(1)).readAllBytes();
              return "s3://simulator/"
                  + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            });
  }

  void intent(double megabytes) {
    store.transaction(
        () ->
            store.create(
                SimulationPayload.KIND,
                "intent",
                new SimulationPayload.Intent(
                    "scenario",
                    "command",
                    "a".repeat(64),
                    120,
                    megabytes,
                    "MSC_SIMULATED_RAW_U8_V1")));
  }

  @Test
  void storesExactDeclaredBytesAndHashOnceOutsideDatabaseTransaction() throws Exception {
    intent(1);
    api.work();
    var saved = api.read("intent");
    assertEquals(1_000_000, content.length);
    assertEquals(content.length, saved.body().byteCount());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
        saved.body().sha256());
    assertEquals("ONBOARD", saved.body().location());
    assertEquals("SIMULATION", saved.body().environment());
    assertEquals(1, store.history("simulation-payload", "intent").size());
    clearInvocations(objects);
    new SimulationPayloadApi(store, json, db, objects).work();
    verifyNoInteractions(objects);
  }

  @Test
  void failedWriteRetriesAndStaleClaimCannotPublish() throws Exception {
    intent(.001);
    var old = api.claim().orElseThrow();
    assertTrue(api.claim().isEmpty());
    db.update("UPDATE simulation_payload_work SET lease_until=now()-interval '1 second'");
    var replacement = api.claim().orElseThrow();
    api.materialize(old);
    assertTrue(store.list("simulation-payload", 10).isEmpty());
    doThrow(new IOException("offline")).when(objects).write(anyString(), any());
    api.materialize(replacement);
    assertEquals(
        "RETRY", db.queryForObject("SELECT status FROM simulation_payload_work", String.class));
    assertTrue(store.list("simulation-payload", 10).isEmpty());
    assertTrue(api.claim().isEmpty());
  }

  @Test
  void metadataFailureAfterUploadRollsBackAndRetryReusesIdenticalBytes() {
    intent(.001);
    db.execute(
        "ALTER TABLE state_history ADD CONSTRAINT reject_payload CHECK (kind <>"
            + " 'simulation-payload')");
    try {
      api.work();
      assertTrue(store.list("simulation-payload", 10).isEmpty());
      assertEquals(
          "RETRY", db.queryForObject("SELECT status FROM simulation_payload_work", String.class));
    } finally {
      db.execute("ALTER TABLE state_history DROP CONSTRAINT reject_payload");
    }
    byte[] first = content.clone();
    db.update("UPDATE simulation_payload_work SET next_attempt_at=now()-interval '1 second'");
    api.work();
    assertArrayEquals(first, content);
    assertEquals(1, store.history("simulation-payload", "intent").size());
    assertEquals(
        "STORED", db.queryForObject("SELECT status FROM simulation_payload_work", String.class));
  }

  @Test
  void invalidSizesDoNotUploadOrPretendToHavePayloads() {
    for (double size : new double[] {0, .0000001, 100}) {
      db.execute("TRUNCATE state_head,state_history,simulation_payload_work");
      intent(size);
      api.work();
      assertEquals(
          "REJECTED",
          db.queryForObject("SELECT status FROM simulation_payload_work", String.class));
      assertTrue(store.list("simulation-payload", 10).isEmpty());
    }
    verifyNoInteractions(objects);
  }
}
