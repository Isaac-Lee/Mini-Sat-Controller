package msc.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneOffset;
import msc.domain.time.MissionInstant;
import org.junit.jupiter.api.Test;

class VersionedUtcTaiClockTest {
  @Test
  void explicitReferenceIntervalControlsConversionAndExpiresClosed() {
    var epoch = Instant.parse("2026-09-11T00:00:00Z");
    var source = java.time.Clock.fixed(epoch, ZoneOffset.UTC);
    var clock =
        new VersionedUtcTaiClock(source, "test-reference-v1", 37, epoch, epoch.plusSeconds(10));
    assertEquals(MissionInstant.tai(epoch.getEpochSecond() + 37), clock.now());
    assertThrows(
        IllegalStateException.class,
        () ->
            new VersionedUtcTaiClock(source, "expired-v1", 37, epoch.minusSeconds(10), epoch)
                .now());
  }
}
