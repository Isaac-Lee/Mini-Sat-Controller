package msc.services.groundoperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.domain.groundoperations.GroundOperations.*;
import msc.platform.*;
import msc.ports.Clock;
import msc.ports.GroundStationPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * SIMULATION {@link GroundStationPort} (ADR-0008) over the durable booking workflow and the
 * Simulator station-booking provider. It is not an RF or real ground-station integration.
 * Reservations are still created via /api/bookings, because a ContactOpportunity lacks the station
 * version, access prediction and volume: {@link #book} resolves the durable booking whose ID is the
 * opportunity ID.
 */
@Component
public final class SimulatedGroundStation implements GroundStationPort {
  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final Clock clock;

  public SimulatedGroundStation(StateStore store, ServiceHttp http, Json json, Clock clock) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  static String passId(String bookingId) {
    return "pass-" + bookingId;
  }

  @Override
  public StationBooking book(ContactOpportunity opportunity) {
    var booking = store.require("booking", opportunity.id(), Booking.class).body();
    var request = booking.request();
    if (!request.spacecraftId().equals(opportunity.spacecraftId().value())
        || !request.stationId().equals(opportunity.stationId())
        || !request.window().equals(opportunity.window()))
      throw ApiException.conflict("Contact opportunity does not match the durable booking");
    var state =
        switch (booking.status()) {
          case CONFIRMED -> BookingState.CONFIRMED;
          case TENTATIVE, REQUESTING, UNKNOWN -> BookingState.TENTATIVE;
          case REJECTED, CANCEL_PENDING, CANCELLED ->
              throw ApiException.conflict("Booking is " + booking.status());
        };
    return new StationBooking(booking.id(), opportunity.id(), state);
  }

  @Override
  public PassSession begin(StationBooking confirmedBooking) {
    String id = passId(confirmedBooking.id());
    var existing = store.find("pass", id, PassRecord.class);
    if (existing.isPresent()) return new PassSession(id, confirmedBooking.id());
    if (confirmedBooking.state() != BookingState.CONFIRMED)
      throw ApiException.conflict("Pass requires a CONFIRMED booking");
    var local = store.require("booking", confirmedBooking.id(), Booking.class).body();
    var provider = provider(local);
    if (provider == null
        || provider.status() != BookingStatus.CONFIRMED
        || !provider.evidenceReference().equals(local.evidenceReference()))
      throw ApiException.conflict("Simulator provider does not hold this booking as CONFIRMED");
    return store.transaction(
        () -> {
          store.lock("booking:" + local.id());
          if (store.find("pass", id, PassRecord.class).isPresent())
            return new PassSession(id, local.id());
          var current = store.require("booking", local.id(), Booking.class).body();
          if (current.status() != BookingStatus.CONFIRMED)
            throw ApiException.conflict("Booking is " + current.status());
          var now = clock.now();
          var window = current.request().window();
          if (now.compareTo(window.start()) < 0)
            throw ApiException.conflict("Contact window has not started");
          if (now.compareTo(window.end()) >= 0)
            throw ApiException.conflict("Contact window has passed; booking is stale");
          var pass =
              new PassRecord(
                  id,
                  local.id(),
                  "SIMULATION",
                  PassStatus.ACTIVE,
                  now,
                  null,
                  0,
                  null,
                  provider.evidenceReference());
          var saved = store.create("pass", id, pass);
          store.event("SimulatedPassBegan", id, saved.version(), UUID.randomUUID(), null, pass);
          return new PassSession(id, local.id());
        });
  }

  @Override
  public PassReport report(PassSession session) {
    var stored = store.require("pass", session.id(), PassRecord.class).body();
    if (!stored.bookingId().equals(session.bookingId()))
      throw ApiException.conflict("Stale pass session handle: booking mismatch");
    if (stored.status() == PassStatus.REPORTED) return report(stored);
    var local = store.require("booking", stored.bookingId(), Booking.class).body();
    var window = local.request().window();
    if (clock.now().compareTo(window.end()) < 0)
      throw ApiException.conflict("Pass is still in its contact window");
    boolean held = false;
    if (local.status() == BookingStatus.CONFIRMED) {
      var provider = provider(local);
      held =
          provider != null
              && provider.status() == BookingStatus.CONFIRMED
              && provider.evidenceReference().equals(stored.evidenceReference());
    }
    boolean completed = held;
    return store.transaction(
        () -> {
          store.lock("booking:" + local.id());
          var current = store.require("pass", session.id(), PassRecord.class);
          if (current.body().status() == PassStatus.REPORTED) return report(current.body());
          // Re-check under the lock: a cancellation may have landed after the provider call.
          boolean confirmed =
              completed
                  && store.require("booking", local.id(), Booking.class).body().status()
                      == BookingStatus.CONFIRMED;
          var pass =
              new PassRecord(
                  stored.id(),
                  stored.bookingId(),
                  "SIMULATION",
                  PassStatus.REPORTED,
                  stored.contactStart(),
                  confirmed ? window.end() : null,
                  confirmed ? bytes(local.request().requestedMegabytes()) : 0,
                  confirmed ? PassOutcome.COMPLETED : PassOutcome.BOOKING_LOST,
                  stored.evidenceReference());
          var saved = store.update("pass", pass.id(), current.version(), pass);
          store.event(
              "SimulatedPassReported", pass.id(), saved.version(), UUID.randomUUID(), null, pass);
          return report(pass);
        });
  }

  private PassReport report(PassRecord pass) {
    return new PassReport(pass.id(), "simulation-pass:" + json.fingerprint(pass));
  }

  /** Decimal megabytes (10^6 bytes), rounded half-up to whole bytes. */
  static long bytes(double megabytes) {
    return BigDecimal.valueOf(megabytes)
        .movePointRight(6)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  /** Provider view of the booking, null when the simulator no longer knows it. */
  private Booking provider(Booking local) {
    try {
      var reply = http.get("simulator", "/internal/station-bookings/" + local.id(), Booking.class);
      if (!reply.id().equals(local.id()) || !reply.request().equals(local.request()))
        throw new ApiException(
            HttpStatus.BAD_GATEWAY, "PROVIDER_MISMATCH", "Simulator reply binding mismatch");
      return reply;
    } catch (RestClientResponseException error) {
      if (error.getStatusCode().value() == 404) return null;
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PROVIDER_FAILED",
          "Simulator HTTP " + error.getStatusCode().value());
    } catch (RestClientException error) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", "Simulator unreachable");
    }
  }
}
