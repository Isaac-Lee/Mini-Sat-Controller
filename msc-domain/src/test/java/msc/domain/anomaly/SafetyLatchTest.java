package msc.domain.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.SafetyPolicy.Reason;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.monitoring.TelemetryObservation.Quality;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class SafetyLatchTest {
  SafetyPolicy policy =
      new SafetyPolicy("sim", 1, 1, 20, 500, 1, 2, 60, "approved simulation policy");

  MissionInstant t(long n) {
    return new MissionInstant(n, 0, TimeScale.TAI);
  }

  @Test
  void twoDistinctUnexpiredOperatorsAreRequiredAndNormalEvidenceCannotAutoClear() {
    var initial = SafetyLatch.initial(policy);
    var first = initial.approve(policy, "one", "reviewed", t(100));
    assertTrue(first.frozen());
    var same = first.approve(policy, "one", "repeated", t(101));
    assertTrue(same.frozen());
    assertEquals(same, same.freeze(Set.of(), true));
    assertFalse(same.approve(policy, "two", "independent review", t(102)).frozen());
    assertTrue(first.approve(policy, "two", "late review", t(160)).frozen());
  }

  @Test
  void newCriticalEvidenceInvalidatesPendingApprovalsAndRelatchesClearedState() {
    var first = SafetyLatch.initial(policy).approve(policy, "one", "reviewed", t(100));
    var incident = first.freeze(Set.of(Reason.SAFE_MODE), true);
    assertTrue(incident.approvals().isEmpty());
    assertEquals(first.generation() + 1, incident.generation());
    assertTrue(incident.approve(policy, "two", "reviewed", t(101)).frozen());
    var clear = first.approve(policy, "two", "reviewed", t(101));
    assertTrue(clear.freeze(Set.of(Reason.LOW_BATTERY), true).frozen());
  }

  @Test
  void thresholdsAndSourceFreshnessAreIndependentReleaseConditions() {
    var binding = new Binding("sim", 1, "simulator:test", Environment.SIMULATION, 60, 0, "test");
    var frame =
        new Frame(
            UUID.randomUUID(),
            "sim",
            1,
            "simulator:test",
            1,
            t(100),
            Quality.GOOD,
            Mode.SAFE,
            19,
            501,
            .5,
            "test");
    var estimate = Estimate.empty(binding).observe(frame, t(100)).estimate();
    assertEquals(
        Set.of(Reason.SAFE_MODE, Reason.LOW_BATTERY, Reason.STORAGE_LIMIT, Reason.LOW_PROPELLANT),
        policy.evaluate(estimate, t(101)));
    assertTrue(policy.evaluate(estimate, t(160)).contains(Reason.STALE_STATE));
    assertTrue(policy.evaluate(Estimate.empty(binding), t(100)).contains(Reason.UNKNOWN_STATE));
  }
}
