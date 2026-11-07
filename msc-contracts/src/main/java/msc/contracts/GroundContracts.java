package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.time.*;

public final class GroundContracts {
  private GroundContracts() {}

  public record Station(
      String id,
      long version,
      double latitudeDegrees,
      double longitudeDegrees,
      double altitudeMeters,
      double minimumElevationDegrees,
      double downlinkMegabytesPerSecond,
      String approvalReference) {
    public Station {
      text(id);
      text(approvalReference);
      if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
          || version < 1
          || !Double.isFinite(latitudeDegrees)
          || Math.abs(latitudeDegrees) > 90
          || !Double.isFinite(longitudeDegrees)
          || Math.abs(longitudeDegrees) > 180
          || !Double.isFinite(altitudeMeters)
          || altitudeMeters < -500
          || altitudeMeters > 10000
          || !Double.isFinite(minimumElevationDegrees)
          || minimumElevationDegrees < 0
          || minimumElevationDegrees >= 90
          || !Double.isFinite(downlinkMegabytesPerSecond)
          || downlinkMegabytesPerSecond <= 0)
        throw new IllegalArgumentException("Invalid station definition");
    }
  }

  public record Reservation(
      String stationId,
      long stationVersion,
      String spacecraftId,
      TimeWindow window,
      String accessPredictionId,
      double requestedMegabytes) {
    public Reservation {
      text(stationId);
      text(spacecraftId);
      text(accessPredictionId);
      Objects.requireNonNull(window);
      if (stationVersion < 1 || !Double.isFinite(requestedMegabytes) || requestedMegabytes <= 0)
        throw new IllegalArgumentException("Invalid booking request");
    }
  }

  public enum BookingStatus {
    TENTATIVE,
    REQUESTING,
    UNKNOWN,
    CONFIRMED,
    REJECTED,
    CANCEL_PENDING,
    CANCELLED
  }

  public record Booking(
      String id,
      Reservation request,
      BookingStatus status,
      String evidenceReference,
      String reason) {
    public Booking {
      text(id);
      Objects.requireNonNull(request);
      Objects.requireNonNull(status);
      Objects.requireNonNull(evidenceReference);
      Objects.requireNonNull(reason);
    }
  }

  public enum PassStatus {
    ACTIVE,
    REPORTED
  }

  public enum PassOutcome {
    COMPLETED,
    BOOKING_LOST
  }

  /**
   * SIMULATION pass session for one confirmed booking. No RF quantities: only simulated contact
   * start/end (TAI), bytes accounted against the booked volume and an outcome. {@code
   * accountedBytes} is the booked request volume (decimal MB x 10^6) for COMPLETED and 0
   * otherwise; it is not a measured reception. BOOKING_LOST has no contact end.
   */
  public record PassRecord(
      String id,
      String bookingId,
      String environment,
      PassStatus status,
      MissionInstant contactStart,
      MissionInstant contactEnd,
      long accountedBytes,
      PassOutcome outcome,
      String evidenceReference) {
    public PassRecord {
      text(id);
      text(bookingId);
      text(evidenceReference);
      Objects.requireNonNull(status);
      Objects.requireNonNull(contactStart).requireTai();
      if (!"SIMULATION".equals(environment)
          || accountedBytes < 0
          || (status == PassStatus.ACTIVE) != (outcome == null)
          || (outcome == PassOutcome.COMPLETED) != (contactEnd != null)
          || (outcome != PassOutcome.COMPLETED && accountedBytes != 0))
        throw new IllegalArgumentException("Invalid SIMULATION pass session");
      if (contactEnd != null && contactEnd.compareTo(contactStart) < 0)
        throw new IllegalArgumentException("Pass contact end precedes start");
    }
  }

  public static java.math.BigDecimal tai(MissionInstant instant) {
    instant.requireTai();
    return java.math.BigDecimal.valueOf(instant.seconds())
        .add(java.math.BigDecimal.valueOf(instant.nanos(), 9));
  }

  public static double seconds(TimeWindow window) {
    return tai(window.end()).subtract(tai(window.start())).doubleValue();
  }

  public static void validateCapacity(Station station, Reservation request) {
    if (!Double.isFinite(seconds(request.window()) * station.downlinkMegabytesPerSecond())
        || !station.id().equals(request.stationId())
        || station.version() != request.stationVersion()
        || seconds(request.window()) > 7200
        || request.requestedMegabytes()
            > seconds(request.window()) * station.downlinkMegabytesPerSecond())
      throw new IllegalArgumentException("Booking exceeds pinned station identity/capacity");
  }
}
