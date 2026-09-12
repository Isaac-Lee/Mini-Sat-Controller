package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.planning.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class SimulationPowerSupplyTest {
  @Test
  void averageEnergyBalanceCannotHideEarlyEclipseBatteryExhaustion() {
    var model =
        new SimulationPlanningContracts.Model(
            "sim-power",
            "v1",
            "SIMULATION",
            MissionPhase.ROUTINE,
            "NOMINAL",
            1000,
            10,
            30,
            0,
            true,
            20,
            40,
            0,
            .5,
            0,
            "explicit-simulation-model");
    var start = MissionInstant.tai(1000);
    var middle = start.plus(new MissionDuration(1800_000_000_000L));
    var end = start.plus(new MissionDuration(3600_000_000_000L));
    var horizon = new TimeWindow(start, end);
    var initial = new ResourceTimeline.Initial(start, 5, 0, 1, "telemetry:1");
    var limits = new ResourceTimeline.Limits(100, 1, 1000, 0);
    var required = Set.of(ResourceTimeline.Resource.BATTERY);
    // The average model falsely sees a constant battery level of 5 Wh.
    var averaged =
        ResourceTimeline.evaluate(
            horizon,
            initial,
            limits,
            List.of(new ResourceTimeline.Supply(horizon, 20, 20, "unsafe-average")),
            List.of(),
            required,
            "model:1");
    assertEquals(ResourceValidation.Status.VALIDATED, averaged.status());
    // With exactly the same 50% illumination, the initial eclipse violates reserve.
    var ordered =
        ResourceTimeline.evaluate(
            horizon,
            initial,
            limits,
            List.of(
                new ResourceTimeline.Supply(new TimeWindow(start, middle), 0, 20, "eclipse"),
                new ResourceTimeline.Supply(new TimeWindow(middle, end), 40, 20, "sunlit")),
            List.of(),
            required,
            "model:1");
    assertEquals(ResourceValidation.Status.REJECTED, ordered.status());
    var conservative =
        ResourceTimeline.evaluate(
            horizon,
            initial,
            limits,
            List.of(model.conservativeSupply(horizon, "model:1")),
            List.of(),
            required,
            "model:1");
    assertEquals(ResourceValidation.Status.REJECTED, conservative.status());
    assertTrue(
        conservative.violations().stream()
            .anyMatch(v -> v.resource() == ResourceTimeline.Resource.BATTERY));
  }
}
