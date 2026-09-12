package msc.domain.time;

import java.util.Objects;

/** Scale-tagged count from the scale's 1970-01-01 origin; not a civil UTC/leap-second converter. */
public record MissionInstant(long seconds, int nanos, TimeScale scale)
    implements Comparable<MissionInstant> {
  public MissionInstant {
    Objects.requireNonNull(scale);
    if (nanos < 0 || nanos >= 1_000_000_000)
      throw new IllegalArgumentException("Invalid nanosecond fraction");
  }

  public static MissionInstant tai(long seconds) {
    return new MissionInstant(seconds, 0, TimeScale.TAI);
  }

  public void requireTai() {
    if (scale != TimeScale.TAI)
      throw new IllegalArgumentException("Explicit conversion to TAI required");
  }

  public int compareTo(MissionInstant other) {
    if (scale != other.scale)
      throw new IllegalArgumentException("Cannot compare different time scales");
    int order = Long.compare(seconds, other.seconds);
    return order == 0 ? Integer.compare(nanos, other.nanos) : order;
  }

  public MissionInstant plus(MissionDuration duration) {
    requireTai();
    long fraction = nanos + duration.nanoseconds() % 1_000_000_000;
    long whole = Math.addExact(seconds, duration.nanoseconds() / 1_000_000_000);
    return new MissionInstant(
        Math.addExact(whole, fraction / 1_000_000_000), (int) (fraction % 1_000_000_000), scale);
  }
}
