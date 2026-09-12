package msc.domain.planning;

import java.util.Objects;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.TimeWindow;

public record ScheduleKey(SpacecraftId spacecraftId, TimeWindow horizon) {
  public ScheduleKey {
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(horizon);
  }
}
