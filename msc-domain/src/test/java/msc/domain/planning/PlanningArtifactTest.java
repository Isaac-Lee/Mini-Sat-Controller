package msc.domain.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.*;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class PlanningArtifactTest {
  ActivityDefinition definition(long version, boolean approved) {
    return new ActivityDefinition(
        new ActivityDefinitionId("image"),
        version,
        "IMAGE",
        approved,
        Set.of(new ResourceId("payload")),
        Set.of(MissionPhase.ROUTINE),
        Set.of("NOMINAL"),
        AuthorityPolicy.RiskClass.LOW,
        "image-template:1");
  }

  PlanCandidate candidate() {
    return PlanCandidate.propose(
        new CandidateId("candidate"),
        new PlanningRunId("run"),
        new RequestId("request"),
        new SpacecraftId("craft"),
        new ActivityId("image"),
        new TimeWindow(MissionInstant.tai(10), MissionInstant.tai(20)),
        definition(1, true),
        MissionPhase.ROUTINE,
        "NOMINAL",
        new FeasibilityEvaluation(FeasibilityEvaluation.Status.NOT_EVALUATED, "pending-models"));
  }

  PlanningDataSnapshot inputs() {
    var references =
        new EnumMap<PlanningDataSnapshot.Input, SnapshotRef>(PlanningDataSnapshot.Input.class);
    for (var input : PlanningDataSnapshot.Input.values())
      references.put(
          input,
          new SnapshotRef(
              new SnapshotId(input.name()), 3, "synthetic-source", MissionInstant.tai(0)));
    return new PlanningDataSnapshot(references);
  }

  @Test
  void runAndCandidateRoundTripRetainExplicitInputAndApprovalContext() {
    var candidate = candidate();
    var run =
        new PlanningRun(
            candidate.runId(),
            candidate.requestId(),
            MissionInstant.tai(0),
            inputs(),
            List.of(),
            List.of(candidate),
            List.of());
    var saved = run.snapshot();
    var restored =
        PlanningRun.restore(
            saved,
            (id, version) -> {
              assertEquals(new ActivityDefinitionId("image"), id);
              assertEquals(1L, version);
              return definition(version, true);
            });
    assertEquals(saved, restored.snapshot());
    assertEquals(10, restored.inputs().references().size());
    assertEquals(
        FeasibilityEvaluation.Status.NOT_EVALUATED,
        restored.candidates().getFirst().feasibility().status());
    assertThrows(UnsupportedOperationException.class, () -> saved.candidates().clear());
  }

  @Test
  void restorationRejectsWrongCatalogUnapprovedModeAndResourceTampering() {
    var saved = candidate().snapshot();
    assertThrows(
        IllegalArgumentException.class, () -> PlanCandidate.restore(saved, definition(2, true)));
    assertThrows(
        IllegalArgumentException.class, () -> PlanCandidate.restore(saved, definition(1, false)));
    var wrongMode =
        new PlanCandidate.Snapshot(
            saved.id(),
            saved.runId(),
            saved.requestId(),
            saved.spacecraftId(),
            saved.activity(),
            saved.feasibility(),
            saved.phase(),
            "SAFE");
    assertThrows(
        IllegalArgumentException.class,
        () -> PlanCandidate.restore(wrongMode, definition(1, true)));
    var activity =
        new ScheduledActivity(
            saved.activity().id(),
            saved.activity().definitionId(),
            1,
            saved.activity().window(),
            Set.of());
    var altered =
        new PlanCandidate.Snapshot(
            saved.id(),
            saved.runId(),
            saved.requestId(),
            saved.spacecraftId(),
            activity,
            saved.feasibility(),
            saved.phase(),
            saved.mode());
    assertThrows(
        IllegalArgumentException.class, () -> PlanCandidate.restore(altered, definition(1, true)));
    var wrongRun =
        new PlanningRun.Snapshot(
            new PlanningRunId("other"),
            saved.requestId(),
            MissionInstant.tai(0),
            inputs(),
            List.of(),
            List.of(saved),
            List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> PlanningRun.restore(wrongRun, (id, version) -> definition(version, true)));
  }
}
