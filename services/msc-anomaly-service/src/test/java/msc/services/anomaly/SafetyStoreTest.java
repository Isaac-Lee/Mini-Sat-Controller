package msc.services.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.domain.anomaly.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.monitoring.TelemetryObservation.Quality;
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
class SafetyStoreTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  SafetyStore safety;
  JdbcTemplate db;
  MissionInstant now = new MissionInstant(1000, 0, TimeScale.TAI);
  SafetyPolicy policy = new SafetyPolicy("sim", 1, 1, 20, 500, 1, 2, 60, "test");

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,inbox,safety_watch");
    var json = new Json(JsonMapper.builder().findAndAddModules().build());
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, () -> now);
    safety = new SafetyStore(store, () -> now, json);
    store.transaction(() -> safety.configure(policy));
  }

  SafetyEvidence.Reading reading(long version, Mode mode, long time) {
    var binding = new Binding("sim", 1, "simulator:test", Environment.SIMULATION, 60, 0, "test");
    var frame =
        new Frame(
            UUID.randomUUID(),
            "sim",
            1,
            "simulator:test",
            version,
            new MissionInstant(time, 0, TimeScale.TAI),
            Quality.GOOD,
            mode,
            100,
            20,
            2,
            "synthetic");
    return new SafetyEvidence.Reading(
        "sim", version, Estimate.empty(binding).observe(frame, now).estimate());
  }

  void observe(SafetyEvidence.Reading r) {
    store.transaction(
        () -> {
          safety.observe(r, UUID.randomUUID(), null);
          return null;
        });
  }

  void clear(SafetyEvidence.Reading r) {
    store.transaction(
        () -> {
          var check = safety.check("sim", Optional.of(r));
          assertTrue(check.currentReasons().isEmpty());
          safety.approve("sim", check.safetyVersion(), "one", "test");
          return null;
        });
    store.transaction(
        () -> {
          var check = safety.check("sim", Optional.of(r));
          safety.approve("sim", check.safetyVersion(), "two", "test");
          return null;
        });
  }

  @Test
  void lateCriticalEvidenceLatchesButReplayedReviewedEvidenceDoesNotRelatch() {
    var nominal = reading(10, Mode.NOMINAL, 999);
    observe(nominal);
    clear(nominal);
    assertFalse(safety.state("sim").body().frozen());
    var critical = reading(9, Mode.SAFE, 998);
    observe(critical);
    assertTrue(safety.state("sim").body().frozen());
    clear(nominal);
    var reviewed = safety.state("sim");
    observe(critical);
    assertEquals(reviewed, safety.state("sim"));
    assertEquals(1, store.list("anomaly", 100).size());
    assertTrue(
        store
            .find(
                "safety-resolution",
                "sim:1:" + reviewed.body().generation(),
                com.fasterxml.jackson.databind.JsonNode.class)
            .isPresent());
    var staleNominal = reading(8, Mode.NOMINAL, 100);
    observe(staleNominal);
    assertEquals(reviewed, safety.state("sim"));
    assertEquals(
        10, store.require("safety-evidence", "sim", SafetyEvidence.Reading.class).body().version());
  }

  @Test
  void freshChecksFailClosedOnOutageAndEvidenceThatHasAdvanced() {
    var r = reading(10, Mode.NOMINAL, 999);
    observe(r);
    clear(r);
    var result = store.transaction(() -> safety.check("sim", Optional.empty()));
    assertFalse(result.clear());
    assertTrue(result.currentReasons().contains(SafetyPolicy.Reason.MONITORING_UNAVAILABLE));
    assertThrows(
        ApiException.class,
        () ->
            store.transaction(
                () -> safety.check("sim", Optional.of(reading(9, Mode.NOMINAL, 998)))));
  }

  @Test
  void evidenceChangesInvalidateApprovalsAndPolicyUpdatesCannotReuseThem() {
    var nominal = reading(1, Mode.NOMINAL, 990);
    observe(nominal);
    store.transaction(() -> safety.approve("sim", safety.state("sim").version(), "one", "review"));
    observe(reading(2, Mode.SAFE, 995));
    assertTrue(safety.state("sim").body().approvals().isEmpty());
    var next = new SafetyPolicy("sim", 2, 1, 25, 500, 1, 2, 60, "changed");
    store.transaction(() -> safety.configure(next));
    assertEquals(2, safety.state("sim").body().policyVersion());
    assertTrue(safety.state("sim").body().frozen());
    assertTrue(store.history("safety-latch", "sim").size() > 2);
  }

  @Test
  void incidentAndEventRollbackWithInboxAndRepeatedChecksAreStable() {
    var nominal = reading(1, Mode.NOMINAL, 990);
    observe(nominal);
    clear(nominal);
    var prior = safety.state("sim");
    UUID id = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  store.receive(id);
                  safety.observe(reading(2, Mode.SAFE, 995), id, id);
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(prior, safety.state("sim"));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    var a = store.transaction(() -> safety.check("sim", Optional.of(nominal)));
    var b = store.transaction(() -> safety.check("sim", Optional.of(nominal)));
    assertEquals(a.safetyVersion(), b.safetyVersion());
    assertTrue(b.clear());
  }

  @Test
  void watchdogDetectsSilenceWithoutNewEventsOrAUserSecurityContext() {
    var normal = reading(1, Mode.NOMINAL, 999);
    observe(normal);
    clear(normal);
    var evidence = org.mockito.Mockito.mock(SafetyEvidence.class);
    org.mockito.Mockito.when(evidence.current("sim")).thenReturn(normal);
    now = new MissionInstant(1060, 0, TimeScale.TAI);
    var watch = new SafetyWatch(db, store, safety, evidence);
    watch.watch();
    assertTrue(safety.state("sim").body().frozen());
    assertTrue(safety.state("sim").body().reasons().contains(SafetyPolicy.Reason.STALE_STATE));
    watch.watch();
    org.mockito.Mockito.verify(evidence, org.mockito.Mockito.times(1)).current("sim");
  }

  @Test
  void competingWatchersHoldOnlyOneRemoteClaim() throws Exception {
    var normal = reading(1, Mode.NOMINAL, 999);
    var evidence = org.mockito.Mockito.mock(SafetyEvidence.class);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.when(evidence.current("sim"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS));
              return normal;
            });
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = threads.submit(() -> new SafetyWatch(db, store, safety, evidence).watch());
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var second = threads.submit(() -> new SafetyWatch(db, store, safety, evidence).watch());
      second.get();
      org.mockito.Mockito.verify(evidence, org.mockito.Mockito.times(1)).current("sim");
      finish.countDown();
      first.get();
    } finally {
      finish.countDown();
    }
  }
}
