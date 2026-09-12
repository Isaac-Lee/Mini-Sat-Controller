package msc.domain.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import msc.domain.referencedata.*;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class OperationalDesignationTest {
  @Test
  void designationChangesWithoutMutatingSolution() {
    var sat = new SpacecraftId("sat");
    var context =
        new EstimateContext(
            MissionInstant.tai(0),
            "GCRF",
            "covariance:fixture",
            "model-v1",
            "config-v1",
            new SnapshotRef(new SnapshotId("obs"), 1, "fixture", MissionInstant.tai(0)));
    var first = new OrbitSolution(new OrbitSolutionId("s1"), sat, context, "state:1");
    var second = new OrbitSolution(new OrbitSolutionId("s2"), sat, context, "state:2");
    var old = new OperationalDesignation(sat, first.id(), 1, MissionInstant.tai(1), "approval:1");
    var next = old.select(second, MissionInstant.tai(2), "approval:2");
    assertEquals(first.id(), old.orbitSolutionId());
    assertEquals(second.id(), next.orbitSolutionId());
    assertEquals(2, next.version());
    assertEquals("state:1", first.stateVectorReference());
    assertSame(context, first.context());
    assertThrows(
        IllegalArgumentException.class, () -> next.select(first, MissionInstant.tai(0), "stale"));
  }
}
