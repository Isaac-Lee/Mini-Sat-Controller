package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.UUID;
import msc.contracts.TaskingContracts.Area;
import msc.contracts.WeatherContracts.*;
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
class WeatherApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  StateStore store;
  JdbcTemplate db;
  WeatherApi api;
  Json json;
  final Area area = new Area("synthetic", 127, 36, 128, 37, "synthetic-test");
  final TimeWindow horizon = new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000));
  final Query query = new Query(area, horizon);
  final org.springframework.security.core.Authentication actor =
      new UsernamePasswordAuthenticationToken("test-admin", "unused");

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,weather_coverage");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    msc.ports.Clock clock = () -> MissionInstant.tai(1000);
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, clock);
    api = new WeatherApi(store, db, clock);
  }

  Forecast forecast(long issue, double cloud) {
    return new Forecast(
        UUID.randomUUID().toString(),
        "SIMULATION",
        MissionInstant.tai(issue),
        horizon,
        area,
        cloud,
        "synthetic-test");
  }

  @Test
  void selectsNewestCoveringSourceAndPreservesPinnedHistory() {
    var first = forecast(800, .1);
    var saved = api.publish(first, "one", actor);
    assertEquals(json.fingerprint(saved), json.fingerprint(api.publish(first, "one", actor)));
    var next = forecast(900, .8);
    api.publish(next, "two", actor);
    assertEquals(next, api.query(query).body());
    assertEquals(first, api.get(first.id()).body());
    assertThrows(ApiException.class, () -> api.publish(next, "one", actor));
    assertEquals(2, db.queryForObject("SELECT count(*) FROM weather_coverage", Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='WeatherForecastPublished'",
            Integer.class));
    assertEquals(
        next,
        api.query(new Query(new Area("subset", 127.1, 36.1, 127.2, 36.2, "test"), horizon)).body());
  }

  @Test
  void requiresWholeSpatialAndNanosecondTemporalCoverage() {
    api.publish(forecast(900, .5), "source", actor);
    var after = new MissionInstant(2000, 1, TimeScale.TAI);
    assertThrows(
        ApiException.class,
        () -> api.query(new Query(area, new TimeWindow(horizon.start(), after))));
    assertThrows(
        ApiException.class,
        () -> api.query(new Query(new Area("outside", 126, 36, 128, 37, "test"), horizon)));
    assertThrows(ApiException.class, () -> api.publish(forecast(1001, .1), "future", actor));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM weather_coverage", Integer.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Forecast(
                UUID.randomUUID().toString(),
                "HARDWARE",
                MissionInstant.tai(900),
                horizon,
                area,
                .1,
                "test"));
    assertThrows(IllegalArgumentException.class, () -> forecast(900, Double.NaN));
  }

  @Test
  void newerPartialCoverageDoesNotHideOlderUsableSourceAndTransactionRollsBack() {
    var usable = forecast(800, .3);
    api.publish(usable, "full", actor);
    var partial =
        new Forecast(
            UUID.randomUUID().toString(),
            "SIMULATION",
            MissionInstant.tai(900),
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(1500)),
            area,
            .1,
            "partial-test");
    api.publish(partial, "partial", actor);
    assertEquals(usable, api.query(query).body());
    assertEquals(partial, api.query(new Query(area, partial.validFor())).body());
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                () -> {
                  api.publish(forecast(950, .9), "rollback", actor);
                  throw new IllegalStateException("after publish");
                }));
    assertEquals(2, db.queryForObject("SELECT count(*) FROM weather_coverage", Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='weather-forecast'", Integer.class));
  }
}
