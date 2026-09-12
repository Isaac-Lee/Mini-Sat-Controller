package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;
import msc.contracts.IlluminationContracts.Aoi;
import msc.contracts.SolarIntervalContracts.Assumptions;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class SolarIntervalContractsTest {
  final TimeWindow horizon = new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000));
  final Aoi extent = new Aoi("extent", -20, 20, -20, 20, 0);
  final Assumptions assumptions = new Assumptions("sat", "m1", "SIMULATION", "model", "a".repeat(64),
      horizon, extent, .001, .0001, 10, "explicit test assumption");

  @Test void exactModelDigestTimeAndGeographicScopeAreRequired() {
    var target = new Aoi("target", -1, 1, -1, 1, 0);
    assertDoesNotThrow(() -> assumptions.requireCoverage("model", "a".repeat(64), target, horizon));
    assertThrows(IllegalArgumentException.class, () -> assumptions.requireCoverage("other", "a".repeat(64), target, horizon));
    assertThrows(IllegalArgumentException.class, () -> assumptions.requireCoverage("model", "b".repeat(64), target, horizon));
    assertThrows(IllegalArgumentException.class, () -> assumptions.requireCoverage("model", "a".repeat(64), target,
        new TimeWindow(MissionInstant.tai(999), MissionInstant.tai(2000))));
    assertThrows(IllegalArgumentException.class, () -> assumptions.requireCoverage("model", "a".repeat(64),
        new Aoi("outside", -21, 1, -1, 1, 0), horizon));
    assertThrows(IllegalArgumentException.class, () -> assumptions.requireCoverage("model", "a".repeat(64),
        new Aoi("altitude", -1, 1, -1, 1, 1), horizon));
  }

  @Test void noHardwareOrZeroErrorFallback() {
    assertThrows(IllegalArgumentException.class, () -> new Assumptions("sat", "m1", "HARDWARE", "model", "a".repeat(64), horizon, extent, .001, .0001, 10, "test"));
    assertThrows(IllegalArgumentException.class, () -> new Assumptions("sat", "m1", "SIMULATION", "model", "a".repeat(64), horizon, extent, .001, 0, 10, "test"));
  }
}
