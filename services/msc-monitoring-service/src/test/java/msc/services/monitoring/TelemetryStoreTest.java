package msc.services.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import msc.domain.monitoring.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TelemetryStoreTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  TelemetryStore telemetry;
  JdbcTemplate db;
  MissionInstant now = new MissionInstant(1000, 0, TimeScale.TAI);
  Binding binding =
      new Binding("sim-craft", 1, "simulator:test", Environment.SIMULATION, 60, 5, "test binding");

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,inbox");
    var json = new Json(JsonMapper.builder().findAndAddModules().build());
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    telemetry = new TelemetryStore(store, () -> now);
    store.transaction(() -> telemetry.bind(binding));
  }

  Frame frame(long seq, long observed) {
    return new Frame(
        UUID.randomUUID(),
        "sim-craft",
        1,
        "simulator:test",
        seq,
        new MissionInstant(observed, 0, TimeScale.TAI),
        TelemetryObservation.Quality.GOOD,
        Mode.NOMINAL,
        100,
        20,
        1,
        "simulation");
  }

  StateStore.State<Receipt> ingest(Frame f) {
    return store.transaction(() -> telemetry.ingest(f, UUID.randomUUID(), null));
  }

  @Test
  void duplicateAndConcurrentFramesDoNotDuplicateFactsOrRegressTheEstimate() throws Exception {
    var a = frame(1, 990);
    var b = frame(2, 995);
    try (var threads = Executors.newFixedThreadPool(3)) {
      var futures =
          List.of(
              threads.submit(() -> ingest(a)),
              threads.submit(() -> ingest(a)),
              threads.submit(() -> ingest(b)));
      for (var future : futures) future.get();
    }
    var result = store.require("telemetry-estimate", "sim-craft", Estimate.class);
    assertEquals(b, result.body().accepted().get().frame());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='telemetry-receipt'", Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='TelemetryFrameAccounted'",
            Integer.class));
    assertEquals(
        Estimate.empty(binding),
        store.version("telemetry-estimate", "sim-craft", 1, Estimate.class).orElseThrow().body());
  }

  @Test
  void reusedSequenceAndIdentityCannotRewriteEvidence() {
    var f = frame(1, 990);
    var receipt = ingest(f);
    assertThrows(ApiException.class, () -> ingest(frame(1, 995)));
    var forged =
        new Frame(
            f.id(),
            f.spacecraftId(),
            1,
            f.source(),
            1,
            f.observedAt(),
            f.quality(),
            f.mode(),
            50,
            20,
            1,
            f.provenance());
    assertThrows(ApiException.class, () -> ingest(forged));
    assertEquals(receipt, ingest(f));
  }

  @Test
  void bindingRevisionClearsEstimateAndAccountsDelayedOldSourceWithoutPoisoningProjection() {
    ingest(frame(1, 990));
    var second =
        new Binding(
            "sim-craft",
            2,
            "simulator:replacement",
            Environment.SIMULATION,
            60,
            5,
            "replacement approved");
    var changed = store.transaction(() -> telemetry.bind(second));
    assertTrue(changed.body().accepted().isEmpty());
    assertEquals(Disposition.SUPERSEDED_BINDING, ingest(frame(2, 995)).body().disposition());
    assertEquals(changed, store.require("telemetry-estimate", "sim-craft", Estimate.class));
  }

  @Test
  void inboxProjectionAndOutboxRollbackTogether() {
    UUID event = UUID.randomUUID();
    Frame frame = frame(1, 990);
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  store.receive(event);
                  telemetry.ingest(frame, event, event);
                  throw new IllegalStateException("injected rollback");
                }));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='telemetry-receipt'", Integer.class));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }
}
