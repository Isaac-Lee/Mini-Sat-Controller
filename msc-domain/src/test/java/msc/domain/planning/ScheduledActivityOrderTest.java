package msc.domain.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.domain.shared.Ids.ActivityId;
import msc.domain.shared.Ids.ResourceId;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;
import org.junit.jupiter.api.Test;

/** See ActivityDefinitionOrderTest for the underlying Set.copyOf per-JVM ordering defect. */
class ScheduledActivityOrderTest {

  private final TimeWindow window = new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(100));

  private ScheduledActivity activity(LinkedHashSet<ResourceId> resources) {
    return new ScheduledActivity(
        new ActivityId("act-1"), new ActivityDefinitionId("imaging"), 1, window, resources);
  }

  @Test
  void exclusiveResourcesAreInFixedNaturalOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<ResourceId>();
    forward.add(new ResourceId("antenna"));
    forward.add(new ResourceId("bus"));
    forward.add(new ResourceId("payload"));
    forward.add(new ResourceId("wheel"));

    var reverse = new LinkedHashSet<ResourceId>();
    reverse.add(new ResourceId("wheel"));
    reverse.add(new ResourceId("payload"));
    reverse.add(new ResourceId("bus"));
    reverse.add(new ResourceId("antenna"));

    var expected =
        List.of(
            new ResourceId("antenna"),
            new ResourceId("bus"),
            new ResourceId("payload"),
            new ResourceId("wheel"));

    assertEquals(expected, List.copyOf(activity(forward).exclusiveResources()));
    assertEquals(expected, List.copyOf(activity(reverse).exclusiveResources()));
    assertEquals(activity(forward), activity(reverse));
  }

  @Test
  void nullSetAndNullElementAreRejected() {
    assertThrows(NullPointerException.class, () -> activity(null));
    var withNull = new LinkedHashSet<ResourceId>();
    withNull.add(new ResourceId("antenna"));
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> activity(withNull));
  }

  @Test
  void resultIsUnmodifiable() {
    var activity = activity(new LinkedHashSet<>());
    assertThrows(
        UnsupportedOperationException.class,
        () -> activity.exclusiveResources().add(new ResourceId("x")));
  }
}
