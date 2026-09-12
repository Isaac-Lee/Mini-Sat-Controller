package msc.domain.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import msc.domain.anomaly.SafetyPolicy.Reason;
import org.junit.jupiter.api.Test;

/**
 * SafetyLatch.reasons feeds fingerprints and public anomaly snapshots the same way
 * ActivityDefinition's Sets do (see ActivityDefinitionOrderTest). This asserts the enum-ordinal
 * golden order survives regardless of insertion order, and that the empty case (a fully cleared
 * latch) still works -- EnumSet.copyOf alone throws on an empty collection.
 */
class SafetyLatchOrderTest {

  private SafetyLatch latch(LinkedHashSet<Reason> reasons) {
    return new SafetyLatch("sim", 1, 1, true, reasons, List.of());
  }

  @Test
  void reasonsAreInEnumOrdinalOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<Reason>();
    forward.add(Reason.SAFE_MODE);
    forward.add(Reason.LOW_BATTERY);
    forward.add(Reason.STORAGE_LIMIT);

    var reverse = new LinkedHashSet<Reason>();
    reverse.add(Reason.STORAGE_LIMIT);
    reverse.add(Reason.LOW_BATTERY);
    reverse.add(Reason.SAFE_MODE);

    var expected = List.of(Reason.SAFE_MODE, Reason.LOW_BATTERY, Reason.STORAGE_LIMIT);

    assertEquals(expected, List.copyOf(latch(forward).reasons()));
    assertEquals(expected, List.copyOf(latch(reverse).reasons()));
    assertEquals(latch(forward), latch(reverse));
  }

  @Test
  void emptyReasonsIsAllowed() {
    assertTrue(latch(new LinkedHashSet<>()).reasons().isEmpty());
  }

  @Test
  void nullSetAndNullElementAreRejected() {
    assertThrows(NullPointerException.class, () -> latch(null));
    var withNull = new LinkedHashSet<Reason>();
    withNull.add(Reason.SAFE_MODE);
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> latch(withNull));
  }
}
