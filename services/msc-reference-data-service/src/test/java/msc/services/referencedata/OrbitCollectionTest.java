package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.concurrent.*;
import msc.contracts.OrbitReferenceContracts.*;
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
class OrbitCollectionTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  JdbcTemplate db;
  StateStore store;
  CelestrakClient provider;
  OrbitReferenceApi api;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,orbit_collection");
    db.update(
        "UPDATE orbit_provider_control SET last_error=NULL,lease_until=NULL,lease_token=NULL");
    var json = new Json(JsonMapper.builder().build());
    msc.ports.Clock clock = () -> new MissionInstant(1789120000, 0, TimeScale.TAI);
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, clock);
    provider = spy(new CelestrakClient(json));
    doReturn(CelestrakClientTest.fixture()).when(provider).fetch(63229);
    api = new OrbitReferenceApi(db, store, provider, clock);
    api.register(63229, new TrackedSatellite(63229, "SPACEEYE-T1", true));
  }

  void due() {
    db.update("UPDATE orbit_collection SET next_attempt_at=now()-interval '1 second'");
  }

  @Test
  void replicasShareClaimCooldownAndImmutableSnapshots() throws Exception {
    try (var threads = Executors.newFixedThreadPool(2)) {
      var a = threads.submit(() -> api.claim(63229));
      var b = threads.submit(() -> api.claim(63229));
      var first = a.get();
      var second = b.get();
      assertNotEquals(first.isPresent(), second.isPresent());
      api.collect(first.orElseGet(second::orElseThrow));
    }
    var snapshot = api.latest(63229);
    assertEquals("SPACEEYE-T1", snapshot.elements().name());
    assertEquals(CelestrakClientTest.fixture(), snapshot.rawJson());
    assertTrue(api.claim(63229).isEmpty());
    due();
    api.collect(api.claim(63229).orElseThrow());
    assertEquals(snapshot, api.latest(63229));
    assertEquals(1, store.history("public-orbit", snapshot.id()).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void regressionDoesNotReplaceLatestAndTransportFailureStopsAllProviderQueries() throws Exception {
    api.collect(api.claim(63229).orElseThrow());
    var original = api.latest(63229);
    due();
    doReturn(CelestrakClientTest.fixture().replace("2026-09-11", "2026-09-10"))
        .when(provider)
        .fetch(63229);
    api.collect(api.claim(63229).orElseThrow());
    assertEquals(original.id(), api.latest(63229).id());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='public-orbit'", Integer.class));
    due();
    doThrow(new java.io.IOException("CelesTrak HTTP 503")).when(provider).fetch(63229);
    api.collect(api.claim(63229).orElseThrow());
    assertNotNull(api.providerStatus().get("last_error"));
    api.register(25544, new TrackedSatellite(25544, "second", true));
    assertTrue(api.claim(25544).isEmpty());
    assertEquals(original.id(), api.latest(63229).id());
    api.resumeProvider();
    assertTrue(api.claim(25544).isPresent());
  }

  @Test
  void staleLeaseCannotWriteAndMissingSatelliteDoesNotBecomeFakeOrbit() throws Exception {
    var claim = api.claim(63229).orElseThrow();
    db.update("UPDATE orbit_collection SET lease_until=now()-interval '1 second'");
    api.collect(claim);
    assertThrows(ApiException.class, () -> api.latest(63229));
    db.update("UPDATE orbit_provider_control SET lease_until=now()-interval '1 second'");
    due();
    doReturn("[]").when(provider).fetch(63229);
    api.collect(api.claim(63229).orElseThrow());
    assertThrows(ApiException.class, () -> api.latest(63229));
    assertTrue(api.claim(63229).isEmpty());
  }
}
