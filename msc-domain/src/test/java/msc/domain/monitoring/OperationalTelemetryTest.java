package msc.domain.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class OperationalTelemetryTest {
  MissionInstant t(long seconds) {
    return new MissionInstant(seconds, 0, TimeScale.TAI);
  }

  Binding binding =
      new Binding(
          "sim-craft", 1, "simulator:test", Environment.SIMULATION, 60, 5, "explicit test binding");

  Frame frame(long seq, long time, TelemetryObservation.Quality quality) {
    return new Frame(
        UUID.randomUUID(),
        "sim-craft",
        1,
        "simulator:test",
        seq,
        t(time),
        quality,
        Mode.NOMINAL,
        100,
        20,
        1,
        "synthetic observation");
  }

  @Test
  void delayedPacketsCannotRollBackAndFreshnessUsesObservationTime() {
    var first = frame(10, 100, TelemetryObservation.Quality.GOOD);
    var state = Estimate.empty(binding).observe(first, t(110)).estimate();
    var delayed = state.observe(frame(9, 90, TelemetryObservation.Quality.GOOD), t(120));
    assertEquals(Disposition.OUT_OF_ORDER, delayed.receipt().disposition());
    assertEquals(state, delayed.estimate());
    assertEquals(Confidence.FRESH, state.confidence(t(159)));
    assertEquals(Confidence.STALE, state.confidence(t(160)));
    assertEquals(Confidence.FRESH, state.confidence(t(120)));
  }

  @Test
  void badQualityNeverReplacesGoodEvidenceButDegradesUntilNewGoodData() {
    var state =
        Estimate.empty(binding)
            .observe(frame(1, 100, TelemetryObservation.Quality.GOOD), t(100))
            .estimate();
    state = state.observe(frame(3, 120, TelemetryObservation.Quality.INVALID), t(120)).estimate();
    assertEquals(Confidence.DEGRADED, state.confidence(t(125)));
    assertEquals(t(100), state.accepted().get().frame().observedAt());
    state = state.observe(frame(2, 110, TelemetryObservation.Quality.GOOD), t(130)).estimate();
    assertEquals(t(110), state.accepted().get().frame().observedAt());
    assertEquals(Confidence.DEGRADED, state.confidence(t(130)));
    state = state.observe(frame(4, 130, TelemetryObservation.Quality.GOOD), t(130)).estimate();
    assertEquals(Confidence.FRESH, state.confidence(t(130)));
  }

  @Test
  void futureEvidenceCannotPoisonOrderingAndSourceBindingIsMandatory() {
    var empty = Estimate.empty(binding);
    var future = empty.observe(frame(1, 10000, TelemetryObservation.Quality.GOOD), t(100));
    assertEquals(Disposition.FUTURE_TIMESTAMP, future.receipt().disposition());
    assertEquals(empty, future.estimate());
    var good = empty.observe(frame(2, 100, TelemetryObservation.Quality.GOOD), t(100));
    assertEquals(Confidence.FRESH, good.estimate().confidence(t(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Estimate.empty(
                    new Binding(
                        "another", 1, "simulator:test", Environment.SIMULATION, 60, 5, "test"))
                .observe(frame(3, 100, TelemetryObservation.Quality.GOOD), t(100)));
  }

  @Test
  void unknownModeAndNegativeResourcesArePreservedButNotUsedAsAnEstimate() {
    var frame =
        new Frame(
            UUID.randomUUID(),
            "sim-craft",
            1,
            "simulator:test",
            1,
            t(100),
            TelemetryObservation.Quality.GOOD,
            Mode.UNKNOWN,
            -10,
            20,
            1,
            "fault injection");
    var result = Estimate.empty(binding).observe(frame, t(100));
    assertEquals(Disposition.BAD_QUALITY, result.receipt().disposition());
    assertEquals(-10, result.receipt().frame().batteryWh());
    assertEquals(Confidence.UNKNOWN, result.estimate().confidence(t(100)));
  }

  @Test
  void simulationEvidenceCannotBeLabelledAsHardware() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Binding("sim-craft", 1, "simulator:test", Environment.HARDWARE, 60, 5, "test"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Binding("sim-craft", 1, "hardware:test", Environment.SIMULATION, 60, 5, "test"));
  }
}
