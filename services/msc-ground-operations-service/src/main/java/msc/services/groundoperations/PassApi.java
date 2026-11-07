package msc.services.groundoperations;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.domain.groundoperations.GroundOperations.*;
import msc.domain.shared.Ids.SpacecraftId;
import msc.platform.*;
import msc.ports.GroundStationPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Operator-driven SIMULATION pass sessions: ACTIVE on begin, REPORTED (terminal) on report. */
@RestController
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SERVICE')")
public class PassApi {
  public record ReportRequest(String bookingId) {
    public ReportRequest {
      msc.domain.shared.Checks.text(bookingId);
    }
  }

  private final StateStore store;
  private final GroundStationPort station;

  public PassApi(StateStore store, GroundStationPort station) {
    this.store = store;
    this.station = station;
  }

  @PostMapping("/api/bookings/{id}/pass")
  public JsonNode begin(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "pass-begin:" + actor.getName();
    var request = Map.of("bookingId", id);
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    // A retried begin returns the existing session even if the booking was cancelled since.
    if (store.find("pass", SimulatedGroundStation.passId(id), PassRecord.class).isPresent())
      return store.idempotent(
          scope,
          key,
          request,
          () -> store.require("pass", SimulatedGroundStation.passId(id), PassRecord.class));
    var booking = store.require("booking", id, Booking.class).body();
    var opportunity =
        new ContactOpportunity(
            id,
            new SpacecraftId(booking.request().spacecraftId()),
            booking.request().stationId(),
            booking.request().window());
    var session = station.begin(station.book(opportunity));
    return store.idempotent(
        scope, key, request, () -> store.require("pass", session.id(), PassRecord.class));
  }

  @PostMapping("/api/passes/{id}/report")
  public JsonNode report(
      @PathVariable String id,
      @RequestBody ReportRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "pass-report:" + actor.getName();
    var body = Map.of("passId", id, "bookingId", request.bookingId());
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    station.report(new PassSession(id, request.bookingId()));
    return store.idempotent(scope, key, body, () -> store.require("pass", id, PassRecord.class));
  }

  @GetMapping("/api/passes/{id}")
  public PassRecord pass(@PathVariable String id) {
    return store.require("pass", id, PassRecord.class).body();
  }
}
