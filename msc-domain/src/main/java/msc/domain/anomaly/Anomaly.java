package msc.domain.anomaly;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

public record Anomaly(
    AnomalyId id,
    SpacecraftId spacecraftId,
    Severity severity,
    MissionInstant declaredAt,
    String evidenceReference) {
  public enum Severity {
    ADVISORY,
    CRITICAL
  }

  public Anomaly {
    Objects.requireNonNull(id);
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(severity);
    Objects.requireNonNull(declaredAt);
    text(evidenceReference);
  }
}
