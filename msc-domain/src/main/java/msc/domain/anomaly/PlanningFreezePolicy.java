package msc.domain.anomaly;

import java.util.Collection;
import msc.domain.shared.Ids.SpacecraftId;

/** A freeze is a safety input; absence of a freeze is not command authorization. */
public final class PlanningFreezePolicy {
  public boolean isFrozen(
      SpacecraftId spacecraft, boolean safeMode, Collection<Anomaly> activeAnomalies) {
    return safeMode
        || activeAnomalies.stream()
            .anyMatch(
                a ->
                    a.spacecraftId().equals(spacecraft)
                        && a.severity() == Anomaly.Severity.CRITICAL);
  }
}
