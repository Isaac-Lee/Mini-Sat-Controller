package msc.services.groundoperations;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;
import msc.contracts.GroundAvailabilityContracts;
import msc.contracts.GroundAvailabilityContracts.*;
import msc.contracts.GroundContracts;
import msc.contracts.GroundContracts.Station;
import msc.domain.time.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
public class AvailabilityApi {
  private final StateStore store;
  private final JdbcTemplate db;
  private final Json json;
  private final msc.ports.Clock clock;

  public AvailabilityApi(StateStore store, JdbcTemplate db, Json json, msc.ports.Clock clock) {
    this.store = store;
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  static MissionInstant instant(BigDecimal value) {
    long seconds = value.setScale(0, RoundingMode.FLOOR).longValueExact();
    int nanos = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValueExact();
    return new MissionInstant(seconds, nanos, TimeScale.TAI);
  }

  @PostMapping({"/api/ground-availability", "/internal/ground-availability"})
  public JsonNode capture(
      @RequestBody Query query,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "ground-availability:" + actor.getName(),
        key,
        query,
        () -> {
          var at = clock.now();
          // One SQL statement gives definitions and allocations the same MVCC snapshot.
          record Row(Station station, Allocation allocation) {}
          var rows =
              db.query(
                  "SELECT s.body::text,a.booking_id,a.start_tai,a.end_tai FROM state_head h JOIN"
                      + " state_head s ON s.kind='station' AND s.id=h.id||':'||(h.body #>> '{}')"
                      + " LEFT JOIN station_allocation a ON a.station_id=h.id AND a.active AND"
                      + " numrange(a.start_tai,a.end_tai,'[)') && numrange(?,?,'[)') WHERE"
                      + " h.kind='station-head' ORDER BY h.id,a.start_tai LIMIT 10001",
                  (r, n) ->
                      new Row(
                          json.read(r.getString(1), Station.class),
                          r.getString(2) == null
                              ? null
                              : new Allocation(
                                  r.getString(2),
                                  new TimeWindow(
                                      instant(r.getBigDecimal(3)), instant(r.getBigDecimal(4))))),
                  GroundContracts.tai(query.horizon().start()),
                  GroundContracts.tai(query.horizon().end()));
          if (rows.size() > 10000)
            throw ApiException.invalid("Availability result exceeds bounded snapshot size");
          var definitions = new LinkedHashMap<String, Station>();
          var allocations = new LinkedHashMap<String, List<Allocation>>();
          for (var row : rows) {
            definitions.put(row.station().id(), row.station());
            var list = allocations.computeIfAbsent(row.station().id(), id -> new ArrayList<>());
            if (row.allocation() != null) list.add(row.allocation());
          }
          var stations = new ArrayList<StationAvailability>();
          definitions.forEach(
              (id, definition) ->
                  stations.add(
                      new StationAvailability(
                          definition,
                          allocations.get(id),
                          GroundAvailabilityContracts.free(query.horizon(), allocations.get(id)))));
          String id = UUID.randomUUID().toString();
          return store.create(
              "ground-availability",
              id,
              new Snapshot(
                  id, at, query, "LOCAL_ALLOCATIONS_REQUIRE_PROVIDER_CONFIRMATION", stations));
        });
  }

  @GetMapping({"/api/ground-availability/{id}", "/internal/ground-availability/{id}"})
  public Snapshot get(@PathVariable String id) {
    return store.require("ground-availability", id, Snapshot.class).body();
  }
}
