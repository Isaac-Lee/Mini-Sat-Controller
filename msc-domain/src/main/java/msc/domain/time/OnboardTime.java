package msc.domain.time;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.TimeCorrelationId;

public record OnboardTime(long ticks, String clockPartition, TimeCorrelationId correlationId) {
  public OnboardTime {
    if (ticks < 0) throw new IllegalArgumentException("Negative ticks");
    text(clockPartition);
    Objects.requireNonNull(correlationId);
  }
}
