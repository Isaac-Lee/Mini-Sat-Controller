package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import msc.contracts.PropellantContracts.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.monitoring.TelemetryObservation.Quality;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PropellantApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  Json json;
  ServiceHttp http;
  PropellantApi api;
  MissionInstant now;
  Model model;
  Estimate source;
  final org.springframework.security.core.Authentication actor =
      new UsernamePasswordAuthenticationToken("service", "unused");
  final Query query = new Query("sim-test", 1, 1);

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    new JdbcTemplate(ds).execute("TRUNCATE state_head,state_history,outbox");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    now = MissionInstant.tai(1000);
    store =
        new StateStore(
            new JdbcTemplate(ds),
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> now);
    http = mock(ServiceHttp.class);
    api = new PropellantApi(store, http, json, () -> now);
    model =
        new Model(
            "sim-test",
            "sim-v1",
            "SIMULATION",
            1,
            new BigDecimal("0.1"),
            new BigDecimal("0.001"),
            1000,
            "synthetic-test");
    var binding =
        new Binding(
            "sim-test", 1, "simulator:test", Environment.SIMULATION, 60, 5, "synthetic-test");
    var frame =
        new Frame(
            UUID.randomUUID(),
            "sim-test",
            1,
            binding.source(),
            1,
            MissionInstant.tai(990),
            Quality.GOOD,
            Mode.NOMINAL,
            100,
            1,
            1,
            "synthetic-test");
    source = Estimate.empty(binding).observe(frame, now).estimate();
    responses(1);
  }

  void responses(long version) {
    when(http.get("mission-definition", "/internal/propellant-models/sim-test", JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sim-test", 1, model)));
    when(http.get("monitoring", "/internal/spacecraft-estimates/sim-test", JsonNode.class))
        .thenReturn(
            json.tree(Map.of("estimate", new StateStore.State<>("sim-test", version, source))));
  }

  @Test
  void derivesExplicitBoundAndPreservesSourceAndReplay() {
    var result = api.estimate(query, "one", actor);
    var snapshot = json.convert(result.get("body"), Snapshot.class);
    assertEquals(0, new BigDecimal("0.89").compareTo(snapshot.lowerBoundAt(now)));
    assertEquals(
        0,
        new BigDecimal("0.889999999999")
            .compareTo(snapshot.lowerBoundAt(new MissionInstant(1000, 1, TimeScale.TAI))));
    assertEquals(BigDecimal.ZERO, snapshot.lowerBoundAt(snapshot.validUntil()));
    assertThrows(
        IllegalArgumentException.class, () -> snapshot.lowerBoundAt(MissionInstant.tai(1991)));
    assertEquals(source, snapshot.source());
    assertEquals(snapshot, api.get(snapshot.id()));
    now = MissionInstant.tai(1100);
    assertEquals(json.fingerprint(result), json.fingerprint(api.estimate(query, "one", actor)));
    assertThrows(ApiException.class, () -> api.estimate(query, "new-key", actor));
  }

  @Test
  void rejectsChangedVersionsAndUnboundSources() {
    responses(2);
    assertThrows(ApiException.class, () -> api.estimate(query, "stale-version", actor));
    responses(1);
    model =
        new Model(
            "sim-test",
            "sim-v1",
            "SIMULATION",
            2,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1000,
            "wrong-binding");
    responses(1);
    assertThrows(ApiException.class, () -> api.estimate(query, "wrong-binding", actor));
    assertTrue(store.list("propellant-estimate", 100).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Model(
                "sim-test",
                "sim-v1",
                "HARDWARE",
                1,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1000,
                "test"));
  }
}
