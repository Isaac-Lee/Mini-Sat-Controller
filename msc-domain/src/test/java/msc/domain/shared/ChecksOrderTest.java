package msc.domain.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Direct unit tests of the Set-ordering helpers used by every canonical Set constructor. */
class ChecksOrderTest {

  enum Color {
    RED,
    GREEN,
    BLUE
  }

  @Test
  void orderedEnumSetIsInOrdinalOrderRegardlessOfInsertionOrder() {
    var reverse = new LinkedHashSet<Color>();
    reverse.add(Color.BLUE);
    reverse.add(Color.RED);
    reverse.add(Color.GREEN);
    assertEquals(
        List.of(Color.RED, Color.GREEN, Color.BLUE),
        List.copyOf(Checks.orderedEnumSet(Color.class, reverse)));
  }

  @Test
  void orderedEnumSetAcceptsEmptyCollection() {
    assertTrue(Checks.orderedEnumSet(Color.class, List.of()).isEmpty());
  }

  @Test
  void orderedEnumSetRejectsNullCollectionAndNullElement() {
    assertThrows(NullPointerException.class, () -> Checks.orderedEnumSet(Color.class, null));
    var withNull = new LinkedHashSet<Color>();
    withNull.add(Color.RED);
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> Checks.orderedEnumSet(Color.class, withNull));
  }

  @Test
  void orderedEnumSetResultIsUnmodifiable() {
    var set = Checks.orderedEnumSet(Color.class, List.of(Color.RED));
    assertThrows(UnsupportedOperationException.class, () -> set.add(Color.BLUE));
  }

  @Test
  void orderedSetWithNaturalOrderSortsStrings() {
    var reverse = new LinkedHashSet<String>();
    reverse.add("wheel");
    reverse.add("antenna");
    reverse.add("bus");
    assertEquals(List.of("antenna", "bus", "wheel"), List.copyOf(Checks.orderedSet(reverse)));
  }

  @Test
  void orderedSetWithComparatorSortsByProjection() {
    record Named(String value) {}
    var reverse = new LinkedHashSet<Named>();
    reverse.add(new Named("wheel"));
    reverse.add(new Named("antenna"));
    var byValue = Checks.orderedSet(reverse, Comparator.comparing(Named::value));
    assertEquals(List.of(new Named("antenna"), new Named("wheel")), List.copyOf(byValue));
  }

  @Test
  void orderedSetRejectsNullCollectionAndNullElement() {
    assertThrows(NullPointerException.class, () -> Checks.orderedSet(null));
    var withNull = new LinkedHashSet<String>();
    withNull.add("a");
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> Checks.orderedSet(withNull));
  }
}
