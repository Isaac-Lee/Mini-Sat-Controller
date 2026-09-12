package msc.services.groundoperations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import msc.contracts.GroundAvailabilityContracts.*;
import msc.contracts.GroundContracts.Station;
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
class AvailabilityApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  JdbcTemplate db;
  AvailabilityApi api;
  Json json;
  final org.springframework.security.core.Authentication actor =
      new UsernamePasswordAuthenticationToken("test", "unused");
  final Query query = new Query(window("100.000000001", "200.000000001"));

  static TimeWindow window(String a, String b) {
    return new TimeWindow(
        AvailabilityApi.instant(new BigDecimal(a)), AvailabilityApi.instant(new BigDecimal(b)));
  }

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,station_allocation,booking_dispatch");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    msc.ports.Clock clock = () -> MissionInstant.tai(90);
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, clock);
    api = new AvailabilityApi(store, db, json, clock);
    store.transaction(
        () -> {
          store.create("station", "test:1", new Station("test", 1, 36, 127, 100, 5, 1, "test-v1"));
          store.create("station", "test:2", new Station("test", 2, 36, 127, 100, 5, 2, "test-v2"));
          return store.create("station-head", "test", 2L);
        });
  }

  void reserve(String id, String start, String end, boolean active) {
    db.update(
        "INSERT INTO station_allocation(booking_id,station_id,start_tai,end_tai,active)"
            + " VALUES(?,'test',?,?,?)",
        id,
        new BigDecimal(start),
        new BigDecimal(end),
        active);
  }

  @Test
  void latestDefinitionsAndAllActiveHoldsShareSnapshotWithExactFreeIntervals() {
    reserve("unresolved", "90", "110", true);
    reserve("pending", "120.000000001", "130.000000001", true);
    reserve("adjacent", "130.000000001", "140.000000001", true);
    reserve("cancelled", "150", "160", false);
    reserve("tail", "190", "210", true);
    var result = api.capture(query, "capture", actor);
    var snapshot = json.convert(result.get("body"), Snapshot.class);
    var station = snapshot.stations().getFirst();
    assertEquals(2, station.station().version());
    assertEquals(4, station.allocations().size());
    assertEquals(
        List.of(window("110", "120.000000001"), window("140.000000001", "190")), station.free());
    assertEquals(snapshot, api.get(snapshot.id()));
    assertEquals(json.fingerprint(result), json.fingerprint(api.capture(query, "capture", actor)));
    db.update("UPDATE station_allocation SET active=false WHERE booking_id='pending'");
    assertEquals(snapshot, api.get(snapshot.id()));
    var changed =
        json.convert(api.capture(query, "new-capture", actor).get("body"), Snapshot.class);
    assertEquals(window("110", "130.000000001"), changed.stations().getFirst().free().getFirst());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void emptyAndFullyOccupiedCalendarsDoNotInventAvailability() {
    var empty = json.convert(api.capture(query, "empty", actor).get("body"), Snapshot.class);
    assertEquals(List.of(query.horizon()), empty.stations().getFirst().free());
    reserve("full", "90", "210", true);
    var full = json.convert(api.capture(query, "full", actor).get("body"), Snapshot.class);
    assertTrue(full.stations().getFirst().free().isEmpty());
    assertThrows(
        ApiException.class, () -> api.capture(new Query(window("110", "120")), "full", actor));
    assertEquals(
        new MissionInstant(-1, 999999999, TimeScale.TAI),
        AvailabilityApi.instant(new BigDecimal("-0.000000001")));
  }
}
