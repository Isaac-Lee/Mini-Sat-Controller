package msc.infrastructure.time;

import java.util.Objects;
import msc.domain.time.*;
import msc.ports.Clock;

/** Explicitly advanced clock for deterministic simulation/tests. */
public final class VirtualClock implements Clock {
  private MissionInstant current;

  public VirtualClock(MissionInstant initial) {
    current = Objects.requireNonNull(initial);
    current.requireTai();
  }

  public synchronized MissionInstant now() {
    return current;
  }

  public synchronized void advance(MissionDuration duration) {
    current = current.plus(duration);
  }
}
