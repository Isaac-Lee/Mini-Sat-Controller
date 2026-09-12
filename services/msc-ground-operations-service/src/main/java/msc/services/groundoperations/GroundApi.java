package msc.services.groundoperations;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.GroundContracts;
import msc.contracts.GroundContracts.*;
import msc.domain.flightdynamics.AccessPrediction;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SERVICE')")
public class GroundApi {
  private final StateStore store;
  private final JdbcTemplate db;
  private final ServiceHttp http;
  private final Clock clock;

  public GroundApi(StateStore store, JdbcTemplate db, ServiceHttp http, Clock clock) {
    this.store = store;
    this.db = db;
    this.http = http;
    this.clock = clock;
  }

  @PostMapping("/api/stations")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode create(
      @RequestBody Station station,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "station:" + actor.getName(),
        key,
        station,
        () -> {
          store.lock("station:" + station.id());
          var head = store.find("station-head", station.id(), Long.class);
          if (station.version() != head.map(s -> s.body() + 1).orElse(1L))
            throw ApiException.conflict("Station version must advance by one");
          var saved = store.create("station", station.id() + ":" + station.version(), station);
          if (head.isEmpty()) store.create("station-head", station.id(), station.version());
          else store.update("station-head", station.id(), head.get().version(), station.version());
          store.event(
              "StationDefinitionPublished",
              station.id(),
              station.version(),
              UUID.randomUUID(),
              null,
              station);
          return saved;
        });
  }

  @GetMapping({
    "/api/stations/{id}/versions/{version}",
    "/internal/stations/{id}/versions/{version}"
  })
  public Station station(@PathVariable String id, @PathVariable long version) {
    return store.require("station", id + ":" + version, Station.class).body();
  }

  @GetMapping("/internal/stations")
  public List<StateStore.State<JsonNode>> stations() {
    return store.list("station", 500);
  }

  @PostMapping({"/api/bookings", "/internal/bookings"})
  public JsonNode reserve(
      @RequestBody Reservation request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "booking:" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var station = station(request.stationId(), request.stationVersion());
    GroundContracts.validateCapacity(station, request);
    var access =
        http.get(
            "flight-dynamics",
            "/internal/access-predictions/" + request.accessPredictionId(),
            AccessPrediction.class);
    var target = access.query().target();
    var tolerance = java.math.BigDecimal.valueOf(access.rootToleranceSeconds());
    boolean covered =
        access.windows().stream()
            .anyMatch(
                window ->
                    GroundContracts.tai(request.window().start())
                                .compareTo(GroundContracts.tai(window.start()).add(tolerance))
                            >= 0
                        && GroundContracts.tai(request.window().end())
                                .compareTo(GroundContracts.tai(window.end()).subtract(tolerance))
                            <= 0);
    if (access.query().kind() != AccessPrediction.Kind.GROUND_CONTACT
        || !access.spacecraftId().equals(request.spacecraftId())
        || !target.id().equals(station.id() + ":" + station.version())
        || target.latitudeDegrees() != station.latitudeDegrees()
        || target.longitudeDegrees() != station.longitudeDegrees()
        || target.altitudeMeters() != station.altitudeMeters()
        || access.query().minimumElevationDegrees() < station.minimumElevationDegrees()
        || !covered)
      throw ApiException.invalid("Booking is not covered by the pinned station contact prediction");
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("station:" + request.stationId());
          if (store.require("station-head", request.stationId(), Long.class).body()
              != request.stationVersion())
            throw ApiException.conflict("Station definition is superseded");
          if (request.window().start().compareTo(clock.now()) <= 0)
            throw ApiException.invalid("Booking must start in the future");
          boolean overlap =
              Boolean.TRUE.equals(
                  db.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM station_allocation WHERE active AND station_id=?"
                          + " AND numrange(start_tai,end_tai,'[)') && numrange(?,?,'[)'))",
                      Boolean.class,
                      request.stationId(),
                      GroundContracts.tai(request.window().start()),
                      GroundContracts.tai(request.window().end())));
          if (overlap) throw ApiException.conflict("Station interval is reserved or unresolved");
          String id = UUID.randomUUID().toString();
          var booking =
              new Booking(
                  id,
                  request,
                  BookingStatus.TENTATIVE,
                  "",
                  "Awaiting external reservation confirmation");
          db.update(
              "INSERT INTO station_allocation(booking_id,station_id,start_tai,end_tai)"
                  + " VALUES(?,?,?,?)",
              id,
              request.stationId(),
              GroundContracts.tai(request.window().start()),
              GroundContracts.tai(request.window().end()));
          var saved = store.create("booking", id, booking);
          db.update("INSERT INTO booking_dispatch(booking_id) VALUES(?)", id);
          store.event(
              "StationBookingRequested", id, saved.version(), UUID.randomUUID(), null, booking);
          return saved;
        });
  }

  @GetMapping({"/api/bookings/{id}", "/internal/bookings/{id}"})
  public Booking booking(@PathVariable String id) {
    return store.require("booking", id, Booking.class).body();
  }

  @GetMapping("/api/bookings/{id}/history")
  public List<StateStore.State<JsonNode>> history(@PathVariable String id) {
    return store.history("booking", id);
  }

  @PostMapping({"/api/bookings/{id}/cancel", "/internal/bookings/{id}/cancel"})
  public JsonNode cancel(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent(
        "booking-cancel:" + actor.getName(),
        key,
        Map.of("id", id),
        () -> {
          db.query(
              "SELECT booking_id FROM booking_dispatch WHERE booking_id=? FOR UPDATE",
              rs -> {},
              id);
          store.lock("booking:" + id);
          var current = store.require("booking", id, Booking.class);
          var booking = current.body();
          if (booking.status() == BookingStatus.CANCELLED
              || booking.status() == BookingStatus.REJECTED) return current;
          var cancelled =
              new Booking(
                  id,
                  booking.request(),
                  BookingStatus.CANCEL_PENDING,
                  booking.evidenceReference(),
                  "Awaiting external cancellation tombstone");
          var saved = store.update("booking", id, current.version(), cancelled);
          // Do not release the local exclusion until the external provider confirms cancellation.
          db.update(
              "UPDATE booking_dispatch SET done=false,next_attempt_at=clock_timestamp() WHERE"
                  + " booking_id=?",
              id);
          return saved;
        });
  }
}
