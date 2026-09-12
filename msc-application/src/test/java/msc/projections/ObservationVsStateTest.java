package msc.projections;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Optional;
import msc.domain.monitoring.TelemetryObservation;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.MissionInstant;
import msc.projections.monitoring.SpacecraftOperationalState;
import org.junit.jupiter.api.Test;

class ObservationVsStateTest {
  @Test
  void delayedObservationDoesNotReplaceProjectionOrBecomeTruth() {
    var sat = new SpacecraftId("sat");
    var state =
        new SpacecraftOperationalState(
            sat,
            Optional.of("NOMINAL"),
            MissionInstant.tai(20),
            SpacecraftOperationalState.Freshness.STALE,
            "projection-inputs:v1");
    var delayed =
        new TelemetryObservation(
            sat,
            "power",
            MissionInstant.tai(10),
            MissionInstant.tai(30),
            TelemetryObservation.Quality.SUSPECT,
            "station:1",
            BigDecimal.TEN,
            "W");
    assertEquals(MissionInstant.tai(10), delayed.observedAt());
    assertEquals(MissionInstant.tai(30), delayed.receivedAt());
    assertEquals(MissionInstant.tai(20), state.asOf());
    assertEquals(SpacecraftOperationalState.Freshness.STALE, state.freshness());
    assertFalse(SpacecraftOperationalState.class.isInstance(delayed));
  }
}
