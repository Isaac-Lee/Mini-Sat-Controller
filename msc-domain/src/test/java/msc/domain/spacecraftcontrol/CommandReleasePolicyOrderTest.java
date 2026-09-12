package msc.domain.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Decision;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason;
import org.junit.jupiter.api.Test;

/**
 * CommandReleasePolicy.Decision.reasons feeds fingerprints the same way ActivityDefinition's Sets
 * do (see ActivityDefinitionOrderTest in msc.domain.missiondefinition). Empty is the common case
 * (an allowed decision), which EnumSet.copyOf alone cannot construct.
 */
class CommandReleasePolicyOrderTest {

  @Test
  void reasonsAreInEnumOrdinalOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<Reason>();
    forward.add(Reason.SAFETY_FROZEN);
    forward.add(Reason.RESOURCE_UNVALIDATED);
    forward.add(Reason.APPROVAL_MISSING);

    var reverse = new LinkedHashSet<Reason>();
    reverse.add(Reason.APPROVAL_MISSING);
    reverse.add(Reason.RESOURCE_UNVALIDATED);
    reverse.add(Reason.SAFETY_FROZEN);

    var expected =
        List.of(Reason.RESOURCE_UNVALIDATED, Reason.SAFETY_FROZEN, Reason.APPROVAL_MISSING);

    var a = new Decision(forward, "evidence-v1");
    var b = new Decision(reverse, "evidence-v1");

    assertEquals(expected, List.copyOf(a.reasons()));
    assertEquals(expected, List.copyOf(b.reasons()));
    assertEquals(a, b);
  }

  @Test
  void emptyReasonsIsAllowedAndMeansAllowed() {
    var decision = new Decision(new LinkedHashSet<>(), "evidence-v1");
    assertTrue(decision.reasons().isEmpty());
    assertTrue(decision.allowed());
  }

  @Test
  void nullSetAndNullElementAreRejected() {
    assertThrows(NullPointerException.class, () -> new Decision(null, "evidence-v1"));
    var withNull = new LinkedHashSet<Reason>();
    withNull.add(Reason.SAFETY_FROZEN);
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> new Decision(withNull, "evidence-v1"));
  }
}
