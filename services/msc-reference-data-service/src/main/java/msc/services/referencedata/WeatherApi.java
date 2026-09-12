package msc.services.referencedata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.WeatherContracts.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
public class WeatherApi {
  private final StateStore store;
  private final JdbcTemplate db;
  private final msc.ports.Clock clock;

  public WeatherApi(StateStore store, JdbcTemplate db, msc.ports.Clock clock) {
    this.store = store;
    this.db = db;
    this.clock = clock;
  }

  @PostMapping("/api/weather/simulation-forecasts")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Forecast forecast,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "weather:" + actor.getName(),
        key,
        forecast,
        () -> {
          if (forecast.issuedAt().compareTo(clock.now()) > 0)
            throw ApiException.invalid("Forecast issue time cannot be in the future");
          var saved = store.create("weather-forecast", forecast.id(), forecast);
          var start = forecast.validFor().start();
          var end = forecast.validFor().end();
          var area = forecast.area();
          db.update(
              "INSERT INTO weather_coverage VALUES(?,?,?,?,?,?,?,?,?,?,?)",
              forecast.id(),
              forecast.issuedAt().seconds(),
              forecast.issuedAt().nanos(),
              start.seconds(),
              start.nanos(),
              end.seconds(),
              end.nanos(),
              area.west(),
              area.south(),
              area.east(),
              area.north());
          store.event(
              "WeatherForecastPublished",
              saved.id(),
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({"/api/weather/forecasts/{id}", "/internal/weather/forecasts/{id}"})
  public StateStore.State<Forecast> get(@PathVariable String id) {
    return store.require("weather-forecast", id, Forecast.class);
  }

  @PostMapping({"/api/weather/query", "/internal/weather/query"})
  public StateStore.State<Forecast> query(@RequestBody Query query) {
    var now = clock.now();
    var start = query.horizon().start();
    var end = query.horizon().end();
    var area = query.area();
    var ids =
        db.queryForList(
            "SELECT snapshot_id FROM weather_coverage WHERE (issued_seconds,issued_nanos)<=(?,?)"
                + " AND (start_seconds,start_nanos)<=(?,?) AND (end_seconds,end_nanos)>=(?,?)"
                + " AND west<=? AND south<=? AND east>=? AND north>=?"
                + " ORDER BY issued_seconds DESC,issued_nanos DESC,snapshot_id LIMIT 1",
            String.class,
            now.seconds(),
            now.nanos(),
            start.seconds(),
            start.nanos(),
            end.seconds(),
            end.nanos(),
            area.west(),
            area.south(),
            area.east(),
            area.north());
    if (ids.isEmpty())
      throw ApiException.missing("No simulation forecast covers the complete area and horizon");
    return get(ids.getFirst());
  }
}
