package msc.platform;

import msc.domain.time.*;
import msc.ports.Clock;

/**
 * A supplied, versioned constant-offset interval; no leap-second inference or unbounded latest
 * offset.
 */
public final class VersionedUtcTaiClock implements Clock {
  private final java.time.Clock sourceClock;
  private final String sourceReference;
  private final int offset;
  private final java.time.Instant from;
  private final java.time.Instant until;

  public VersionedUtcTaiClock(
      java.time.Clock sourceClock,
      String sourceReference,
      int offset,
      java.time.Instant from,
      java.time.Instant until) {
    this.sourceClock = java.util.Objects.requireNonNull(sourceClock);
    this.sourceReference = msc.domain.shared.Checks.text(sourceReference);
    if (!from.isBefore(until) || offset < 0 || offset > 1000)
      throw new IllegalArgumentException("Invalid time reference interval");
    this.offset = offset;
    this.from = from;
    this.until = until;
  }

  public MissionInstant now() {
    var utc = sourceClock.instant();
    if (utc.isBefore(from) || !utc.isBefore(until))
      throw new IllegalStateException(
          "Time reference expired or not yet valid: " + sourceReference);
    return new MissionInstant(
        Math.addExact(utc.getEpochSecond(), offset), utc.getNano(), TimeScale.TAI);
  }

  public String sourceReference() {
    return sourceReference;
  }
}
