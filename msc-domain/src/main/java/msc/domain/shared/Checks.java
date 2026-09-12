package msc.domain.shared;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class Checks {
  private Checks() {}

  public static String text(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Nonblank text required");
    return value;
  }

  public static long positive(long value) {
    if (value < 1) throw new IllegalArgumentException("Positive value required");
    return value;
  }

  /**
   * Canonicalizes an enum-valued {@code Set} field to a fixed, JVM-stable iteration order
   * (ordinal order via {@link EnumSet}), instead of {@code Set.copyOf}'s per-JVM randomized
   * order. Sets serialize to JSON arrays, and array order is significant to {@code Json.canonical}
   * and therefore to fingerprints and public snapshots, so an unstable iteration order leaks into
   * both. Rejects a null set or a null element, matching {@code Set.copyOf}'s contract. Works for
   * an empty set, unlike {@code EnumSet.copyOf}.
   */
  public static <E extends Enum<E>> Set<E> orderedEnumSet(Class<E> type, Collection<E> values) {
    Objects.requireNonNull(values, "Set required");
    var ordered = EnumSet.noneOf(type);
    for (E value : values) ordered.add(Objects.requireNonNull(value, "Set element required"));
    return Collections.unmodifiableSet(ordered);
  }

  /**
   * Canonicalizes a {@code Set} field to a fixed, JVM-stable iteration order (sorted by {@code
   * order}), instead of {@code Set.copyOf}'s per-JVM randomized order. See {@link
   * #orderedEnumSet(Class, Collection)} for why this matters. Rejects a null set or a null
   * element, matching {@code Set.copyOf}'s contract.
   */
  public static <T> Set<T> orderedSet(Collection<T> values, Comparator<? super T> order) {
    Objects.requireNonNull(values, "Set required");
    var ordered = new TreeSet<T>(order);
    for (T value : values) ordered.add(Objects.requireNonNull(value, "Set element required"));
    return Collections.unmodifiableSet(ordered);
  }

  /** {@link #orderedSet(Collection, Comparator)} using natural order. */
  public static Set<String> orderedSet(Collection<String> values) {
    return orderedSet(values, Comparator.naturalOrder());
  }
}
