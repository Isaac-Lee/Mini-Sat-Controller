package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.GroundContracts;
import msc.contracts.GroundContracts.*;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Stateful external-station simulator: real persistence, conflicts, tombstones and lost replies.
 */
@RestController
public class StationSimulatorApi {
  private final StateStore store;
  private final Json json;
  private final JdbcTemplate db;
  private final Clock clock;

  public StationSimulatorApi(StateStore store, Json json, JdbcTemplate db, Clock clock) {
    this.store = store;
    this.json = json;
    this.db = db;
    this.clock = clock;
  }

  @PostMapping("/api/simulation/stations")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode station(
      @RequestBody Station station,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "sim-station:" + actor.getName(),
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
          return saved;
        });
  }

  public enum FaultMode {
    NONE,
    LOSE_NEXT_RESERVATION_RESPONSE,
    WRONG_NEXT_RESERVATION_RECEIPT
  }

  public record Fault(String stationId, FaultMode mode) {
    public Fault {
      msc.domain.shared.Checks.text(stationId);
      Objects.requireNonNull(mode);
    }
  }

  @PostMapping("/api/simulation/station-faults")
  @PreAuthorize("hasRole('ADMIN')")
  public void fault(@RequestBody Fault fault) {
    store.transaction(
        () -> {
          store.lock("fault:" + fault.stationId());
          var old = store.find("fault", fault.stationId(), Fault.class);
          if (old.isEmpty()) store.create("fault", fault.stationId(), fault);
          else store.update("fault", fault.stationId(), old.get().version(), fault);
          return null;
        });
  }

  private FaultMode takeFault(String station) {
    return store.transaction(
        () -> {
          store.lock("fault:" + station);
          var old = store.find("fault", station, Fault.class);
          if (old.isEmpty() || old.get().body().mode() == FaultMode.NONE) return FaultMode.NONE;
          store.update("fault", station, old.get().version(), new Fault(station, FaultMode.NONE));
          return old.get().body().mode();
        });
  }

  @PostMapping("/internal/station-bookings/{id}")
  public Booking reserve(
      @PathVariable String id,
      @RequestBody Reservation request,
      @RequestHeader("Idempotency-Key") String key) {
    var response =
        store.idempotent(
            "sim-booking",
            key,
            Map.of("id", id, "request", request),
            () ->
                store.transaction(
                    () -> {
                      store.lock("station:" + request.stationId());
                      store.lock("booking:" + id);
                      var old = store.find("booking", id, Booking.class);
                      if (old.isPresent()) {
                        if (!old.get().body().request().equals(request))
                          throw ApiException.conflict("Booking ID reused with different content");
                        return old.get().body();
                      }
                      var station =
                          store
                              .require(
                                  "station",
                                  request.stationId() + ":" + request.stationVersion(),
                                  Station.class)
                              .body();
                      GroundContracts.validateCapacity(station, request);
                      if (store.require("station-head", request.stationId(), Long.class).body()
                          != request.stationVersion())
                        throw ApiException.conflict("Station definition is superseded");
                      if (request.window().start().compareTo(clock.now()) <= 0)
                        throw ApiException.conflict("Station reservation must start in the future");
                      boolean overlap =
                          Boolean.TRUE.equals(
                              db.queryForObject(
                                  "SELECT EXISTS(SELECT 1 FROM station_allocation WHERE active AND"
                                      + " station_id=? AND numrange(start_tai,end_tai,'[)') &&"
                                      + " numrange(?,?,'[)'))",
                                  Boolean.class,
                                  request.stationId(),
                                  GroundContracts.tai(request.window().start()),
                                  GroundContracts.tai(request.window().end())));
                      if (overlap)
                        throw ApiException.conflict("Simulator station is already reserved");
                      db.update(
                          "INSERT INTO station_allocation(booking_id,station_id,start_tai,end_tai)"
                              + " VALUES(?,?,?,?)",
                          id,
                          request.stationId(),
                          GroundContracts.tai(request.window().start()),
                          GroundContracts.tai(request.window().end()));
                      var booked =
                          new Booking(
                              id,
                              request,
                              BookingStatus.CONFIRMED,
                              "simulator-receipt:" + id,
                              "External simulator reservation committed");
                      store.create("booking", id, booked);
                      store.event(
                          "SimulatedStationReserved", id, 1, UUID.randomUUID(), null, booked);
                      return booked;
                    }));
    var fault = takeFault(request.stationId());
    if (fault == FaultMode.WRONG_NEXT_RESERVATION_RECEIPT)
      return new Booking(
          "wrong-" + id,
          request,
          BookingStatus.CONFIRMED,
          "simulated-corrupt-receipt",
          "Deliberately mismatched response for verification");
    if (fault == FaultMode.LOSE_NEXT_RESERVATION_RESPONSE)
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "SIMULATED_LOST_REPLY",
          "Reservation may have committed; reconcile by booking ID");
    return booking(id);
  }

  @GetMapping("/internal/station-bookings/{id}")
  public Booking booking(@PathVariable String id) {
    return store.require("booking", id, Booking.class).body();
  }

  @PostMapping("/internal/station-bookings/{id}/cancel")
  public Booking cancel(
      @PathVariable String id,
      @RequestBody Reservation request,
      @RequestHeader("Idempotency-Key") String key) {
    return json.convert(
        store.idempotent(
            "sim-booking-cancel",
            key,
            Map.of("id", id, "request", request),
            () -> {
              store.lock("station:" + request.stationId());
              store.lock("booking:" + id);
              var old = store.find("booking", id, Booking.class);
              if (old.isPresent() && !old.get().body().request().equals(request))
                throw ApiException.conflict("Cancellation binding mismatch");
              if (old.isPresent() && old.get().body().status() == BookingStatus.CANCELLED)
                return old.get().body();
              var cancelled =
                  new Booking(
                      id,
                      request,
                      BookingStatus.CANCELLED,
                      "simulator-cancellation:" + id,
                      "Cancellation tombstone prevents a delayed create from reviving this"
                          + " booking");
              if (old.isEmpty()) store.create("booking", id, cancelled);
              else store.update("booking", id, old.get().version(), cancelled);
              db.update("UPDATE station_allocation SET active=false WHERE booking_id=?", id);
              store.event(
                  "SimulatedStationCancelled",
                  id,
                  old.map(s -> s.version() + 1).orElse(1L),
                  UUID.randomUUID(),
                  null,
                  cancelled);
              return cancelled;
            }),
        Booking.class);
  }
}
