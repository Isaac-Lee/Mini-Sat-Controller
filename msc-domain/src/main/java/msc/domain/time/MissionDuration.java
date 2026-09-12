package msc.domain.time;

public record MissionDuration(long nanoseconds) {
  public MissionDuration {
    if (nanoseconds < 0) throw new IllegalArgumentException("Negative duration");
  }
}
