package msc.domain.planning;

import static msc.domain.shared.Checks.orderedSet;
import static msc.domain.shared.Checks.positive;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import msc.domain.shared.Ids.*;
import msc.domain.time.TimeWindow;

public record ScheduledActivity(
    ActivityId id,
    ActivityDefinitionId definitionId,
    long definitionVersion,
    TimeWindow window,
    Set<ResourceId> exclusiveResources) {
  public ScheduledActivity {
    Objects.requireNonNull(id);
    Objects.requireNonNull(definitionId);
    positive(definitionVersion);
    Objects.requireNonNull(window);
    // Canonical, JVM-stable Set order: see Checks.orderedSet.
    exclusiveResources = orderedSet(exclusiveResources, Comparator.comparing(ResourceId::value));
  }
}
