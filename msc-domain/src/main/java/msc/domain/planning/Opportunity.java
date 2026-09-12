package msc.domain.planning;

import java.util.Objects;
import msc.domain.shared.Ids.*;
import msc.domain.time.TimeWindow;

public record Opportunity(SpacecraftId spacecraftId, AoiId aoiId, TimeWindow window) {
  public Opportunity {
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(aoiId);
    Objects.requireNonNull(window);
  }
}
