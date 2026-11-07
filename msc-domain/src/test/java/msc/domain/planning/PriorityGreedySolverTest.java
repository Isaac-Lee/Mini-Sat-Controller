package msc.domain.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class PriorityGreedySolverTest {
  private static final ResourceTimeline.Result VALIDATED = resources(ResourceValidation.Status.VALIDATED);

  private static ResourceTimeline.Result resources(ResourceValidation.Status status) {
    return new ResourceTimeline.Result(status, "initial", "model", Set.of(), List.of(), List.of());
  }

  private static TimeWindow window(long start, long end) {
    return new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(end));
  }

  private static PlanCandidate candidate(
      String id, String request, String craft, long start, long end) {
    return candidate(
        id, request, craft, start, end, FeasibilityEvaluation.Status.NOT_EVALUATED);
  }

  private static PlanCandidate candidate(
      String id,
      String request,
      String craft,
      long start,
      long end,
      FeasibilityEvaluation.Status feasibility) {
    return PlanCandidate.propose(
        new CandidateId(id),
        new PlanningRunId("run-" + craft),
        new RequestId(request),
        new SpacecraftId(craft),
        new ActivityId("activity-" + id),
        window(start, end),
        new ActivityDefinition(
            new ActivityDefinitionId("imaging"),
            1,
            "IMAGING_STRIP",
            true,
            Set.of(new ResourceId("payload")),
            Set.of(MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            AuthorityPolicy.RiskClass.LOW,
            "template:imaging:v1"),
        MissionPhase.ROUTINE,
        "NOMINAL",
        new FeasibilityEvaluation(feasibility, "fixture"));
  }

  private static PriorityGreedySolver.Demand demand(
      String request, int priority, PlanCandidate... candidates) {
    return new PriorityGreedySolver.Demand(
        new RequestId(request),
        priority,
        Arrays.stream(candidates)
            .map(c -> new PriorityGreedySolver.Option(c, VALIDATED))
            .toList());
  }

  private static String selected(PriorityGreedySolver.Proposal proposal) {
    return proposal.candidateId().map(CandidateId::value).orElse("none");
  }

  @Test
  void higherPriorityWinsAndTiesBreakByIdRegardlessOfInputOrder() {
    var low = demand("r-b", 10, candidate("b1", "r-b", "sat-1", 10, 20));
    var tieA = demand("r-a", 50, candidate("a1", "r-a", "sat-1", 10, 20));
    var tieC = demand("r-c", 50, candidate("c1", "r-c", "sat-1", 10, 20));
    var first = PriorityGreedySolver.solve(List.of(low, tieC, tieA), List.of());
    var second = PriorityGreedySolver.solve(List.of(tieA, low, tieC), List.of());
    assertEquals(first, second);
    assertEquals(PriorityGreedySolver.SCOPE, first.scope());
    assertEquals("GREEDY_HEURISTIC_NOT_OPTIMAL", first.scope());
    var order = first.proposals().stream().map(p -> p.requestId().value()).toList();
    assertEquals(List.of("r-a", "r-c", "r-b"), order);
    assertEquals("a1", selected(first.proposals().get(0)));
    assertEquals("none", selected(first.proposals().get(1)));
    assertEquals("none", selected(first.proposals().get(2)));
  }

  @Test
  void conflictReassignsToAnotherSpacecraftInsteadOfDropping() {
    var high = demand("r-high", 90, candidate("h1", "r-high", "sat-1", 10, 20));
    var low =
        demand(
            "r-low",
            20,
            candidate("l1", "r-low", "sat-1", 15, 25),
            candidate("l2", "r-low", "sat-2", 15, 25));
    var result = PriorityGreedySolver.solve(List.of(low, high), List.of());
    var lowProposal = result.proposals().get(1);
    assertEquals("l2", selected(lowProposal));
    assertEquals("sat-2", lowProposal.spacecraftId().orElseThrow().value());
    assertTrue(lowProposal.reassigned());
    assertFalse(result.proposals().get(0).reassigned());
    assertTrue(lowProposal.decisions().getFirst().rationale().contains("r-high"));
  }

  @Test
  void committedActivityForcesReassignmentAndAdjacentWindowsDoNotConflict() {
    var key = new ScheduleKey(new SpacecraftId("sat-1"), window(0, 100));
    var committed =
        MissionSchedule.empty(key)
            .commit(
                candidate("c0", "r-old", "sat-1", 10, 20, FeasibilityEvaluation.Status.FEASIBLE),
                new AssignmentId("a0"),
                MissionInstant.tai(0));
    var result =
        PriorityGreedySolver.solve(
            List.of(
                demand(
                    "r-new",
                    50,
                    candidate("n1", "r-new", "sat-1", 12, 18),
                    candidate("n2", "r-new", "sat-2", 12, 18)),
                demand("r-next", 40, candidate("x1", "r-next", "sat-1", 20, 30))),
            List.of(committed));
    assertEquals("n2", selected(result.proposals().get(0)));
    assertTrue(result.proposals().get(0).reassigned());
    assertTrue(
        result.proposals().get(0).decisions().getFirst().rationale().contains("committed"));
    assertEquals("x1", selected(result.proposals().get(1)));
  }

  @Test
  void ineligibleOptionsAreSkippedWithoutCountingAsReassignment() {
    var rejected =
        new PriorityGreedySolver.Option(
            candidate("e1", "r-e", "sat-1", 10, 20),
            resources(ResourceValidation.Status.REJECTED));
    var infeasible =
        new PriorityGreedySolver.Option(
            candidate("e0", "r-e", "sat-1", 0, 5, FeasibilityEvaluation.Status.INFEASIBLE),
            VALIDATED);
    var ok = new PriorityGreedySolver.Option(candidate("e2", "r-e", "sat-2", 30, 40), VALIDATED);
    var result =
        PriorityGreedySolver.solve(
            List.of(new PriorityGreedySolver.Demand(new RequestId("r-e"), 1, List.of(ok, rejected, infeasible))),
            List.of());
    var proposal = result.proposals().getFirst();
    assertEquals("e2", selected(proposal));
    assertFalse(proposal.reassigned());
    assertTrue(proposal.decisions().get(0).rationale().contains("infeasible"));
    assertTrue(proposal.decisions().get(1).rationale().contains("REJECTED"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PriorityGreedySolver.solve(
                List.of(demand("r-e", 1, ok.candidate()), demand("r-e", 2)), List.of()));
  }
}
