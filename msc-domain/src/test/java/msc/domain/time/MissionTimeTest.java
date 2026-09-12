package msc.domain.time;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MissionTimeTest {
  @Test
  void mixedScalesRequireConversion() {
    var utc = new MissionInstant(100, 0, TimeScale.UTC);
    assertThrows(IllegalArgumentException.class, () -> utc.compareTo(MissionInstant.tai(100)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeWindow(utc, new MissionInstant(200, 0, TimeScale.UTC)));
    assertThrows(IllegalArgumentException.class, () -> utc.plus(new MissionDuration(1)));
  }

  @Test
  void durationCarriesNanosecondsWithoutWallClock() {
    assertEquals(
        new MissionInstant(11, 1, TimeScale.TAI),
        new MissionInstant(10, 999_999_999, TimeScale.TAI).plus(new MissionDuration(2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeWindow(MissionInstant.tai(2), MissionInstant.tai(2)));
    assertThrows(
        ArithmeticException.class,
        () -> MissionInstant.tai(Long.MAX_VALUE).plus(new MissionDuration(1_000_000_000)));
  }
}
