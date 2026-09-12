package msc.domain.spacecraftcontrol;

import java.util.Objects;
import java.util.Optional;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

/** Ground-side belief only. Missing evidence gives UNKNOWN, not FAILED. */
public record OnboardScheduleModel(
    SpacecraftId spacecraftId,
    Optional<CommandLoadId> believedLoaded,
    ExecutionOutcome outcome,
    MissionInstant asOf) {
  public enum Reconciliation {
    MATCHED,
    DIVERGENCE_DETECTED,
    UNKNOWN
  }

  public OnboardScheduleModel {
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(believedLoaded);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(asOf);
  }

  public Reconciliation reconcile(Optional<CommandLoadId> observedLoaded) {
    if (believedLoaded.isEmpty() || observedLoaded.isEmpty()) return Reconciliation.UNKNOWN;
    return believedLoaded.equals(observedLoaded)
        ? Reconciliation.MATCHED
        : Reconciliation.DIVERGENCE_DETECTED;
  }
}
