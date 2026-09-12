package msc.domain.time;

import java.util.Objects;

/** Half-open [start, end) physical interval. Adjacent activities do not overlap. */
public record TimeWindow(MissionInstant start, MissionInstant end) {
  public TimeWindow {
    Objects.requireNonNull(start);
    Objects.requireNonNull(end);
    start.requireTai();
    end.requireTai();
    if (start.compareTo(end) >= 0) throw new IllegalArgumentException("Empty or reversed window");
  }

  public boolean overlaps(TimeWindow other) {
    return start.compareTo(other.end) < 0 && other.start.compareTo(end) < 0;
  }

  public boolean contains(TimeWindow other) {
    return start.compareTo(other.start) <= 0 && end.compareTo(other.end) >= 0;
  }
}
