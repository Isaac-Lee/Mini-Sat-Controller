package msc.domain.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.*;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;
import org.junit.jupiter.api.Test;

class ExecutionBeliefTest {
  @Test
  void unknownAndDivergenceAreNotFailureOrConfirmation() {
    var load = new CommandLoadId("load");
    var model =
        new OnboardScheduleModel(
            new SpacecraftId("sat"),
            Optional.of(load),
            ExecutionOutcome.EXECUTION_BELIEVED,
            MissionInstant.tai(10));
    assertEquals(OnboardScheduleModel.Reconciliation.UNKNOWN, model.reconcile(Optional.empty()));
    assertEquals(
        OnboardScheduleModel.Reconciliation.DIVERGENCE_DETECTED,
        model.reconcile(Optional.of(new CommandLoadId("different"))));
    assertEquals(OnboardScheduleModel.Reconciliation.MATCHED, model.reconcile(Optional.of(load)));
    assertEquals(ExecutionOutcome.EXECUTION_BELIEVED, model.outcome());
    var evidence =
        new VerificationRecord(
            load,
            ExecutionOutcome.UNKNOWN,
            MissionInstant.tai(2),
            MissionInstant.tai(10),
            "contact-gap");
    assertNotEquals(ExecutionOutcome.FAILED, evidence.outcome());
  }

  @Test
  void criticalAnomalyOrSafeModeFreezesOnlyAffectedSpacecraft() {
    var policy = new PlanningFreezePolicy();
    var sat = new SpacecraftId("sat");
    var anomaly =
        new Anomaly(
            new AnomalyId("a"), sat, Anomaly.Severity.CRITICAL, MissionInstant.tai(0), "evidence");
    assertTrue(policy.isFrozen(sat, false, List.of(anomaly)));
    assertTrue(policy.isFrozen(sat, true, List.of()));
    assertFalse(policy.isFrozen(new SpacecraftId("other"), false, List.of(anomaly)));
  }
}
