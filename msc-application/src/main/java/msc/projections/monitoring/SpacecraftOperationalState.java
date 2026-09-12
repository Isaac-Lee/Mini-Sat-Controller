package msc.projections.monitoring;

import java.util.Objects;
import java.util.Optional;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.MissionInstant;

/** Derived belief with explicit freshness; never an authoritative aggregate. */
public record SpacecraftOperationalState(
    SpacecraftId spacecraftId,
    Optional<String> mode,
    MissionInstant asOf,
    Freshness freshness,
    String evidenceReference) {
  public enum Freshness {
    FRESH,
    STALE,
    UNKNOWN
  }

  public SpacecraftOperationalState {
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(asOf);
    Objects.requireNonNull(freshness);
    msc.domain.shared.Checks.text(evidenceReference);
  }
}
