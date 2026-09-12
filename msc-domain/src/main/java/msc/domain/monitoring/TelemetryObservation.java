package msc.domain.monitoring;

import static msc.domain.shared.Checks.text;

import java.math.BigDecimal;
import java.util.Objects;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.MissionInstant;

/** Arrival order does not imply observation order or authoritative spacecraft state. */
public record TelemetryObservation(
    SpacecraftId spacecraftId,
    String parameter,
    MissionInstant observedAt,
    MissionInstant receivedAt,
    Quality quality,
    String source,
    BigDecimal value,
    String unit) {
  public enum Quality {
    GOOD,
    SUSPECT,
    INVALID,
    UNKNOWN
  }

  public TelemetryObservation {
    Objects.requireNonNull(spacecraftId);
    text(parameter);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(receivedAt);
    Objects.requireNonNull(quality);
    text(source);
    Objects.requireNonNull(value);
    text(unit);
  }
}
