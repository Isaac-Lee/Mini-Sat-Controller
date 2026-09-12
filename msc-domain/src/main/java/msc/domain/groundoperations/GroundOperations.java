package msc.domain.groundoperations;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.TimeWindow;

/** Separate lifecycle contracts, not one GroundContact aggregate. */
public final class GroundOperations {
  private GroundOperations() {}

  public record ContactOpportunity(
      String id, SpacecraftId spacecraftId, String stationId, TimeWindow window) {
    public ContactOpportunity {
      text(id);
      Objects.requireNonNull(spacecraftId);
      text(stationId);
      Objects.requireNonNull(window);
    }
  }

  public enum BookingState {
    TENTATIVE,
    CONFIRMED
  }

  public record StationBooking(String id, String opportunityId, BookingState state) {
    public StationBooking {
      text(id);
      text(opportunityId);
      Objects.requireNonNull(state);
    }
  }

  public record PassSession(String id, String bookingId) {
    public PassSession {
      text(id);
      text(bookingId);
    }
  }

  public record PassReport(String sessionId, String evidenceManifest) {
    public PassReport {
      text(sessionId);
      text(evidenceManifest);
    }
  }
}
