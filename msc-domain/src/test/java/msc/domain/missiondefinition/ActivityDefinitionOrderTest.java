package msc.domain.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.AuthorityPolicy.RiskClass;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.domain.shared.Ids.ResourceId;
import org.junit.jupiter.api.Test;

/**
 * Set.copyOf's iteration order is a per-JVM randomized salt (ImmutableCollections.SetN), so it
 * leaks into JSON array order and therefore into fingerprints and public catalog snapshots
 * (Json.canonical sorts object members but deliberately preserves array order). These constructors
 * must instead produce a FIXED, JVM-stable order regardless of insertion order. This asserts the
 * literal golden order, not merely two same-process requests agreeing (two requests in one JVM do
 * not exercise the per-JVM salt at all).
 */
class ActivityDefinitionOrderTest {

  private ActivityDefinition definition(
      LinkedHashSet<ResourceId> resources, LinkedHashSet<MissionPhase> phases, LinkedHashSet<String> modes) {
    return new ActivityDefinition(
        new ActivityDefinitionId("imaging"),
        1,
        "IMAGING_STRIP",
        true,
        resources,
        phases,
        modes,
        RiskClass.LOW,
        "imaging:1");
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

    var a = definition(forward, new LinkedHashSet<>(), new LinkedHashSet<>());
    var b = definition(reverse, new LinkedHashSet<>(), new LinkedHashSet<>());

    assertEquals(expected, List.copyOf(a.exclusiveResources()));
    assertEquals(expected, List.copyOf(b.exclusiveResources()));
    assertEquals(a.exclusiveResources(), b.exclusiveResources());
  }

  @Test
  void allowedPhasesAreInEnumOrdinalOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<MissionPhase>();
    forward.add(MissionPhase.LEOP);
    forward.add(MissionPhase.ROUTINE);
    forward.add(MissionPhase.EOL);

    var reverse = new LinkedHashSet<MissionPhase>();
    reverse.add(MissionPhase.EOL);
    reverse.add(MissionPhase.ROUTINE);
    reverse.add(MissionPhase.LEOP);

    var expected = List.of(MissionPhase.LEOP, MissionPhase.ROUTINE, MissionPhase.EOL);

    var a = definition(new LinkedHashSet<>(), forward, new LinkedHashSet<>());
    var b = definition(new LinkedHashSet<>(), reverse, new LinkedHashSet<>());

    assertEquals(expected, List.copyOf(a.allowedPhases()));
    assertEquals(expected, List.copyOf(b.allowedPhases()));
  }

  @Test
  void allowedModesAreInNaturalStringOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<String>();
    forward.add("AUTONOMOUS");
    forward.add("MANUAL");
    forward.add("NOMINAL");

    var reverse = new LinkedHashSet<String>();
    reverse.add("NOMINAL");
    reverse.add("MANUAL");
    reverse.add("AUTONOMOUS");

    var expected = List.of("AUTONOMOUS", "MANUAL", "NOMINAL");

    var a = definition(new LinkedHashSet<>(), new LinkedHashSet<>(), forward);
    var b = definition(new LinkedHashSet<>(), new LinkedHashSet<>(), reverse);

    assertEquals(expected, List.copyOf(a.allowedModes()));
    assertEquals(expected, List.copyOf(b.allowedModes()));
  }

  @Test
  void emptySetsAreAllowedForAllThreeFields() {
    var definition = definition(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
    assertTrue(definition.exclusiveResources().isEmpty());
    assertTrue(definition.allowedPhases().isEmpty());
    assertTrue(definition.allowedModes().isEmpty());
  }

  @Test
  void nullSetsAndNullElementsAreStillRejected() {
    assertThrows(
        NullPointerException.class,
        () -> definition(null, new LinkedHashSet<>(), new LinkedHashSet<>()));
    assertThrows(
        NullPointerException.class,
        () -> definition(new LinkedHashSet<>(), null, new LinkedHashSet<>()));
    assertThrows(
        NullPointerException.class,
        () -> definition(new LinkedHashSet<>(), new LinkedHashSet<>(), null));

    var modesWithNull = new LinkedHashSet<String>();
    modesWithNull.add("NOMINAL");
    modesWithNull.add(null);
    assertThrows(
        NullPointerException.class,
        () -> definition(new LinkedHashSet<>(), new LinkedHashSet<>(), modesWithNull));
  }

  @Test
  void resultingSetsAreUnmodifiable() {
    var definition = definition(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
    assertThrows(
        UnsupportedOperationException.class,
        () -> definition.allowedModes().add("EXTRA"));
  }
}
