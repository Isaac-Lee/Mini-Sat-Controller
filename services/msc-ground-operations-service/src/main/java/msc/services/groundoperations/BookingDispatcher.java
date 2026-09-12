package msc.services.groundoperations;

import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class BookingDispatcher {
  private record Claim(String id, UUID token) {}

  private final StateStore store;
  private final JdbcTemplate db;
  private final ServiceHttp http;

  public BookingDispatcher(StateStore store, JdbcTemplate db, ServiceHttp http) {
    this.store = store;
    this.db = db;
    this.http = http;
  }

  @Scheduled(fixedDelayString = "${msc.ground.dispatch-delay-ms:250}")
  public void dispatch() {
    var claim =
        store.transaction(
            () -> {
              var due =
                  db.queryForList(
                      "SELECT booking_id FROM booking_dispatch WHERE NOT done AND"
                          + " next_attempt_at<=clock_timestamp() AND (lease_until IS NULL OR"
                          + " lease_until<=clock_timestamp()) ORDER BY next_attempt_at FOR UPDATE"
                          + " SKIP LOCKED LIMIT 1",
                      String.class);
              if (due.isEmpty()) return null;
              var token = UUID.randomUUID();
              String id = due.getFirst();
              db.update(
                  "UPDATE booking_dispatch SET lease_until=clock_timestamp()+interval '30"
                      + " seconds',lease_token=?,attempts=attempts+1 WHERE booking_id=?",
                  token,
                  id);
              return new Claim(id, token);
            });
    if (claim == null) return;
    var command =
        store.transaction(
            () -> {
              lockDispatch(claim.id());
              store.lock("booking:" + claim.id());
              if (!owns(claim)) return null;
              var current = store.require("booking", claim.id(), Booking.class);
              var booking = current.body();
              if (booking.status() == BookingStatus.TENTATIVE) {
                var requesting =
                    new Booking(
                        booking.id(),
                        booking.request(),
                        BookingStatus.REQUESTING,
                        "",
                        "Durable dispatch claim recorded before network call");
                store.update("booking", booking.id(), current.version(), requesting);
                return booking;
              }
              return booking;
            });
    if (command == null) return;
    Booking reply = null;
    String failure = "";
    BookingStatus failureStatus = BookingStatus.UNKNOWN;
    try {
      if (command.status() == BookingStatus.CANCEL_PENDING)
        reply =
            http.post(
                "simulator",
                "/internal/station-bookings/" + command.id() + "/cancel",
                command.request(),
                command.id() + ":cancel",
                Booking.class);
      else if (command.status() == BookingStatus.TENTATIVE)
        reply =
            http.post(
                "simulator",
                "/internal/station-bookings/" + command.id(),
                command.request(),
                command.id() + ":reserve",
                Booking.class);
      else
        reply = http.get("simulator", "/internal/station-bookings/" + command.id(), Booking.class);
      if (!reply.id().equals(command.id())
          || !reply.request().equals(command.request())
          || reply.evidenceReference().isBlank()
          || !Set.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED).contains(reply.status()))
        throw new IllegalStateException("Provider reply binding/status mismatch");
    } catch (RestClientResponseException error) {
      reply = null;
      failure = "Provider HTTP " + error.getStatusCode().value();
      if (command.status() != BookingStatus.CANCEL_PENDING) {
        if (error.getStatusCode().value() == 404 && command.status() != BookingStatus.TENTATIVE)
          failureStatus = BookingStatus.TENTATIVE;
        else if (error.getStatusCode().is4xxClientError()) failureStatus = BookingStatus.REJECTED;
      }
    } catch (Exception error) {
      reply = null;
      failure = error.getClass().getSimpleName();
    }
    final Booking response = reply;
    final String reason = failure;
    final BookingStatus fallback = failureStatus;
    store.transaction(
        () -> {
          lockDispatch(claim.id());
          store.lock("booking:" + claim.id());
          if (!owns(claim)) return null;
          var current = store.require("booking", claim.id(), Booking.class);
          var local = current.body();
          BookingStatus next = response == null ? fallback : response.status();
          if (local.status() == BookingStatus.CANCEL_PENDING
              && (response == null || response.status() != BookingStatus.CANCELLED))
            next = BookingStatus.CANCEL_PENDING;
          var changed =
              new Booking(
                  local.id(),
                  local.request(),
                  next,
                  response == null ? local.evidenceReference() : response.evidenceReference(),
                  response == null ? reason : response.reason());
          var saved = store.update("booking", local.id(), current.version(), changed);
          boolean terminal =
              Set.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED, BookingStatus.REJECTED)
                  .contains(next);
          if (next == BookingStatus.CANCELLED || next == BookingStatus.REJECTED)
            db.update("UPDATE station_allocation SET active=false WHERE booking_id=?", local.id());
          db.update(
              "UPDATE booking_dispatch SET"
                  + " done=?,lease_until=NULL,lease_token=NULL,next_attempt_at=clock_timestamp()+interval"
                  + " '2 seconds' WHERE booking_id=? AND lease_token=?",
              terminal,
              local.id(),
              claim.token());
          store.event(
              next == BookingStatus.CONFIRMED
                  ? "StationBookingConfirmed"
                  : next == BookingStatus.CANCELLED
                      ? "StationBookingCancelled"
                      : next == BookingStatus.REJECTED
                          ? "StationBookingRejected"
                          : "StationBookingUnresolved",
              local.id(),
              saved.version(),
              UUID.randomUUID(),
              null,
              changed);
          return null;
        });
  }

  private void lockDispatch(String id) {
    db.query("SELECT booking_id FROM booking_dispatch WHERE booking_id=? FOR UPDATE", rs -> {}, id);
  }

  private boolean owns(Claim claim) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM booking_dispatch WHERE booking_id=? AND lease_token=? AND"
                + " lease_until>clock_timestamp())",
            Boolean.class,
            claim.id(),
            claim.token()));
  }
}
