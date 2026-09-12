package msc.domain.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.*;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class MissionScheduleTest {
  private final SpacecraftId spacecraft = new SpacecraftId("sat-1");
  private final ScheduleKey key = new ScheduleKey(spacecraft, window(0, 100));

  private static TimeWindow window(long start, long end) {
    return new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(end));
  }

  private ActivityDefinition definition(boolean approved) {
    return new ActivityDefinition(
        new ActivityDefinitionId("imaging"),
        1,
        "IMAGING_STRIP",
        approved,
        Set.of(new ResourceId("payload")),
        Set.of(MissionPhase.ROUTINE),
        Set.of("NOMINAL"),
        AuthorityPolicy.RiskClass.LOW,
        "template:imaging:v1");
  }

  private PlanCandidate candidate(String id, long start, long end) {
    return PlanCandidate.propose(
        new CandidateId(id),
        new PlanningRunId("run-" + id),
        new RequestId("request-" + id),
        spacecraft,
        new ActivityId("activity-" + id),
        window(start, end),
        definition(true),
        MissionPhase.ROUTINE,
        "NOMINAL",
        new FeasibilityEvaluation(true, "fixture-only"));
  }

  @Test
  void rejectsExclusiveOverlapWithoutChangingHistory() {
    var original =
        MissionSchedule.empty(key)
            .commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            original.commit(candidate("b", 19, 30), new AssignmentId("b"), MissionInstant.tai(0)));
    assertEquals(1, original.version());
    assertEquals(1, original.activities().size());
  }

  @Test
  void adjacentActivitiesAreAllowedAndReplanLeavesHistoryImmutable() {
    var empty = MissionSchedule.empty(key);
    var first = empty.commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(0));
    var second = first.commit(candidate("b", 20, 30), new AssignmentId("b"), MissionInstant.tai(0));
    assertEquals(0, empty.version());
    assertEquals(1, first.version());
    assertEquals(2, second.version());
    assertEquals(1, first.assignments().size());
    assertEquals(2, second.assignments().size());
    assertThrows(UnsupportedOperationException.class, () -> first.assignments().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> first.activities().getFirst().exclusiveResources().clear());
    assertEquals(
        ResourceValidation.Status.NOT_EVALUATED, second.resourceValidation().reservoirStatus());
  }

  @Test
  void assignmentContainsTypedReferencesAndProposalIsNotCommitment() {
    var candidate = candidate("a", 10, 20);
    var empty = MissionSchedule.empty(key);
    assertTrue(empty.assignments().isEmpty());
    var assignment =
        empty
            .commit(candidate, new AssignmentId("a"), MissionInstant.tai(0))
            .assignments()
            .getFirst();
    assertEquals(candidate.id(), assignment.candidateId());
    assertEquals(candidate.requestId(), assignment.requestId());
    assertEquals(candidate.runId(), assignment.runId());
    assertEquals(candidate.activity().id(), assignment.activityId());
    assertFalse(PlanCandidate.class.isInstance(assignment));
    assertTrue(
        Arrays.stream(Assignment.class.getRecordComponents())
            .allMatch(c -> c.getType().getEnclosingClass() == msc.domain.shared.Ids.class));
  }

  @Test
  void observationRequestLifecycleDoesNotContainSchedulingState() {
    var received =
        new ObservationRequest(
            new RequestId("r"),
            new AoiId("daejeon"),
            1,
            "coverage fixture",
            Optional.empty(),
            0,
            ObservationRequest.InteractionPreference.AUTO,
            ObservationRequest.Status.RECEIVED);
    var accepted = received.accept();
    assertEquals(ObservationRequest.Status.RECEIVED, received.status());
    assertEquals(ObservationRequest.Status.ACCEPTED, accepted.status());
    assertThrows(IllegalStateException.class, accepted::accept);
    assertTrue(
        Arrays.stream(ObservationRequest.class.getRecordComponents())
            .noneMatch(c -> c.getType().getPackageName().contains("planning")));
  }

  @Test
  void unapprovedOrDisallowedActivityCannotBecomeProposal() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PlanCandidate.propose(
                new CandidateId("x"),
                new PlanningRunId("r"),
                new RequestId("q"),
                spacecraft,
                new ActivityId("a"),
                window(1, 2),
                definition(false),
                MissionPhase.ROUTINE,
                "NOMINAL",
                new FeasibilityEvaluation(true, "fixture")));
    assertThrows(
        IllegalArgumentException.class,
        () -> definition(true).requireSchedulable(MissionPhase.CONTINGENCY, "SAFE"));
  }

  @Test
  void rejectsOutOfHorizonAndFrozenChanges() {
    var frozen =
        MissionSchedule.empty(key)
            .commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(25));
    assertThrows(
        IllegalArgumentException.class,
        () -> frozen.commit(candidate("b", 21, 24), new AssignmentId("b"), MissionInstant.tai(25)));
    assertThrows(
        IllegalArgumentException.class,
        () -> frozen.commit(candidate("b", 30, 40), new AssignmentId("b"), MissionInstant.tai(24)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            frozen.commit(candidate("b", 99, 101), new AssignmentId("b"), MissionInstant.tai(25)));
  }

  @Test
  void inputsAndPlanningOutputsAreDefensivelyCopied() {
    var refs =
        new EnumMap<PlanningDataSnapshot.Input, SnapshotRef>(PlanningDataSnapshot.Input.class);
    for (var input : PlanningDataSnapshot.Input.values())
      refs.put(
          input,
          new SnapshotRef(new SnapshotId(input.name()), 1, "fixture", MissionInstant.tai(0)));
    var inputs = new PlanningDataSnapshot(refs);
    refs.clear();
    var candidate = candidate("a", 10, 20);
    var candidates = new ArrayList<>(List.of(candidate));
    var run =
        new PlanningRun(
            candidate.runId(),
            candidate.requestId(),
            MissionInstant.tai(0),
            inputs,
            List.of(),
            candidates,
            List.of(new DecisionRecord(candidate.id(), "fixture")));
    candidates.clear();
    assertEquals(10, inputs.references().size());
    assertEquals(1, run.candidates().size());
    assertThrows(UnsupportedOperationException.class, () -> run.candidates().clear());
    assertThrows(IllegalArgumentException.class, () -> new PlanningDataSnapshot(Map.of()));
  }

  @Test
  void rejectsDuplicateIdsAndWrongSpacecraftAndInfeasibleCandidate() {
    var proposal = candidate("a", 10, 20);
    var first =
        MissionSchedule.empty(key).commit(proposal, new AssignmentId("a"), MissionInstant.tai(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> first.commit(proposal, new AssignmentId("b"), MissionInstant.tai(0)));
    var other = MissionSchedule.empty(new ScheduleKey(new SpacecraftId("other"), key.horizon()));
    assertThrows(
        IllegalArgumentException.class,
        () -> other.commit(proposal, new AssignmentId("a"), MissionInstant.tai(0)));
    var infeasible =
        PlanCandidate.propose(
            new CandidateId("bad"),
            proposal.runId(),
            proposal.requestId(),
            spacecraft,
            new ActivityId("bad"),
            window(30, 40),
            definition(true),
            MissionPhase.ROUTINE,
            "NOMINAL",
            new FeasibilityEvaluation(false, "fixture-rejected"));
    assertThrows(
        IllegalArgumentException.class,
        () -> first.commit(infeasible, new AssignmentId("bad"), MissionInstant.tai(0)));
  }

  @Test
  void durableSnapshotRoundTripPreservesVersionAndDefensiveCopies() {
    var original =
        MissionSchedule.empty(key)
            .commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(0));
    var restored = MissionSchedule.restore(original.snapshot());
    assertEquals(original.snapshot(), restored.snapshot());
    assertThrows(
        UnsupportedOperationException.class, () -> restored.snapshot().activities().clear());
    var invalid =
        new MissionSchedule.Snapshot(
            key,
            1,
            MissionInstant.tai(0),
            original.activities(),
            List.of(),
            ResourceValidation.notEvaluated());
    assertThrows(IllegalArgumentException.class, () -> MissionSchedule.restore(invalid));
  }

  @Test
  void unevaluatedProposalIsDistinctFromRejectionAndCannotCommit() {
    var pending =
        new FeasibilityEvaluation(
            FeasibilityEvaluation.Status.NOT_EVALUATED, "sensor-and-resource-evaluation-pending");
    var rejected = new FeasibilityEvaluation(false, "computed-resource-violation");
    assertNotEquals(pending.status(), rejected.status());
    assertFalse(pending.feasible());
    var proposal =
        PlanCandidate.propose(
            new CandidateId("pending"),
            new PlanningRunId("run"),
            new RequestId("request"),
            spacecraft,
            new ActivityId("pending"),
            window(30, 40),
            definition(true),
            MissionPhase.ROUTINE,
            "NOMINAL",
            pending);
    var schedule = MissionSchedule.empty(key);
    var before = schedule.snapshot();
    assertThrows(
        IllegalArgumentException.class,
        () -> schedule.commit(proposal, new AssignmentId("pending"), MissionInstant.tai(0)));
    assertEquals(before, schedule.snapshot());
    assertEquals(
        FeasibilityEvaluation.Status.FEASIBLE,
        new FeasibilityEvaluation(true, "evaluated").status());
  }

  @Test
  void corruptedPersistedOverlapAndVersionAreRejected() {
    var a = candidate("a", 10, 20);
    var b = candidate("b", 15, 25);
    var assignments =
        List.of(
            new Assignment(
                new AssignmentId("a"), a.id(), a.requestId(), a.runId(), a.activity().id()),
            new Assignment(
                new AssignmentId("b"), b.id(), b.requestId(), b.runId(), b.activity().id()));
    var invalid =
        new MissionSchedule.Snapshot(
            key,
            2,
            MissionInstant.tai(0),
            List.of(a.activity(), b.activity()),
            assignments,
            ResourceValidation.notEvaluated());
    assertThrows(IllegalArgumentException.class, () -> MissionSchedule.restore(invalid));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MissionSchedule.restore(
                new MissionSchedule.Snapshot(
                    key,
                    -1,
                    MissionInstant.tai(0),
                    List.of(),
                    List.of(),
                    ResourceValidation.notEvaluated())));
  }

  @Test
  void withdrawalPreservesExecutedHistoryAndProducesANewVersion() {
    var original =
        MissionSchedule.empty(key)
            .commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(0))
            .commit(candidate("b", 30, 40), new AssignmentId("b"), MissionInstant.tai(0));
    var revised = original.withdrawFuture(new RequestId("request-b"), MissionInstant.tai(25));
    assertEquals(3, revised.version());
    assertEquals(1, revised.activities().size());
    assertEquals(2, original.activities().size());
    assertEquals(original.activities().getFirst(), revised.activities().getFirst());
    assertSame(revised, revised.withdrawFuture(new RequestId("request-a"), MissionInstant.tai(25)));
    assertEquals(revised.snapshot(), MissionSchedule.restore(revised.snapshot()).snapshot());
    assertThrows(
        IllegalArgumentException.class,
        () -> revised.withdrawFuture(new RequestId("request-a"), MissionInstant.tai(24)));
  }

  @Test
  void resourceValidationCannotOmitAnActivityProfile() {
    var schedule =
        MissionSchedule.empty(key)
            .commit(candidate("a", 10, 20), new AssignmentId("a"), MissionInstant.tai(0));
    var result =
        ResourceTimeline.evaluate(
            schedule,
            new ResourceTimeline.Initial(MissionInstant.tai(0), 100, 0, 2, "estimate-v1"),
            new ResourceTimeline.Limits(100, 20, 100, 1),
            List.of(new ResourceTimeline.Supply(key.horizon(), 0, 0, "forecast-v1")),
            List.of(),
            Set.of(ResourceTimeline.Resource.BATTERY),
            "model-v1");
    assertEquals(ResourceValidation.Status.NOT_EVALUATED, result.status());
  }
}
