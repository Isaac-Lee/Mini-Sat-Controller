package msc.domain.planning;

import java.util.*;
import msc.domain.shared.Ids.*;

/**
 * Deterministic priority-first greedy proposal heuristic. It is not an optimizer: a higher-ranked
 * request keeps its first conflict-free option even when a different choice would place more
 * work. Output is proposals only; committing still goes through {@link MissionSchedule#commit}.
 */
public final class PriorityGreedySolver {
  public static final String SCOPE = "GREEDY_HEURISTIC_NOT_OPTIMAL";

  private PriorityGreedySolver() {}

  /** Candidate plus its own reservoir forecast against the committed schedule context. */
  public record Option(PlanCandidate candidate, ResourceTimeline.Result resources) {
    public Option {
      Objects.requireNonNull(candidate);
      Objects.requireNonNull(resources);
    }
  }

  /** One request revision; priority is 0..100, higher first. */
  public record Demand(RequestId requestId, int priority, List<Option> options) {
    public Demand {
      Objects.requireNonNull(requestId);
      if (priority < 0 || priority > 100)
        throw new IllegalArgumentException("Priority must be 0..100");
      options = List.copyOf(options);
      if (options.stream().anyMatch(o -> !o.candidate().requestId().equals(requestId)))
        throw new IllegalArgumentException("Option belongs to another request");
    }
  }

  /**
   * Selected option, if any. {@code reassigned} means the first-ranked eligible option was on
   * another spacecraft and lost to a conflict.
   */
  public record Proposal(
      RequestId requestId,
      int priority,
      Optional<CandidateId> candidateId,
      Optional<PlanningRunId> runId,
      Optional<SpacecraftId> spacecraftId,
      boolean reassigned,
      List<DecisionRecord> decisions) {
    public Proposal {
      decisions = List.copyOf(decisions);
    }
  }

  public record Result(String scope, List<Proposal> proposals) {
    public Result {
      proposals = List.copyOf(proposals);
    }
  }

  private record Occupied(SpacecraftId spacecraftId, ScheduledActivity activity, String owner) {}

  /** Requests by priority desc then ID; options by window start then candidate ID. */
  public static Result solve(List<Demand> demands, List<MissionSchedule> committed) {
    var seenRequests = new HashSet<RequestId>();
    var seenCandidates = new HashSet<CandidateId>();
    for (var demand : demands) {
      if (!seenRequests.add(demand.requestId()))
        throw new IllegalArgumentException("Duplicate request demand");
      for (var option : demand.options())
        if (!seenCandidates.add(option.candidate().id()))
          throw new IllegalArgumentException("Duplicate candidate");
    }
    var occupied = new ArrayList<Occupied>();
    for (var schedule : committed)
      for (var activity : schedule.activities())
        occupied.add(
            new Occupied(
                schedule.key().spacecraftId(), activity, "committed activity " + activity.id()));
    var ordered =
        demands.stream()
            .sorted(
                Comparator.comparingInt(Demand::priority)
                    .reversed()
                    .thenComparing(d -> d.requestId().value()))
            .toList();
    var proposals = new ArrayList<Proposal>();
    for (var demand : ordered) {
      var options =
          demand.options().stream()
              .sorted(
                  Comparator.comparing((Option o) -> o.candidate().activity().window().start())
                      .thenComparing(o -> o.candidate().id().value()))
              .toList();
      var decisions = new ArrayList<DecisionRecord>();
      SpacecraftId firstEligible = null;
      Option selected = null;
      for (var option : options) {
        var candidate = option.candidate();
        if (candidate.feasibility().status() == FeasibilityEvaluation.Status.INFEASIBLE) {
          decisions.add(new DecisionRecord(candidate.id(), "SKIPPED: evaluated infeasible"));
          continue;
        }
        if (option.resources().status() != ResourceValidation.Status.VALIDATED) {
          decisions.add(
              new DecisionRecord(
                  candidate.id(), "SKIPPED: resource forecast " + option.resources().status()));
          continue;
        }
        if (firstEligible == null) firstEligible = candidate.spacecraftId();
        var conflict = conflict(occupied, candidate);
        if (conflict.isPresent()) {
          decisions.add(
              new DecisionRecord(candidate.id(), "SKIPPED: conflicts with " + conflict.get()));
          continue;
        }
        selected = option;
        break;
      }
      if (selected == null) {
        proposals.add(
            new Proposal(
                demand.requestId(),
                demand.priority(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                decisions));
        continue;
      }
      var candidate = selected.candidate();
      boolean reassigned = !candidate.spacecraftId().equals(firstEligible);
      decisions.add(
          new DecisionRecord(
              candidate.id(),
              (reassigned ? "PROPOSED_REASSIGNED" : "PROPOSED")
                  + ": greedy heuristic choice, not optimal"));
      occupied.add(
          new Occupied(
              candidate.spacecraftId(),
              candidate.activity(),
              "proposal for request " + demand.requestId().value()));
      proposals.add(
          new Proposal(
              demand.requestId(),
              demand.priority(),
              Optional.of(candidate.id()),
              Optional.of(candidate.runId()),
              Optional.of(candidate.spacecraftId()),
              reassigned,
              decisions));
    }
    return new Result(SCOPE, proposals);
  }

  // ponytail: per-option resource forecasts do not compose two proposals on one spacecraft;
  // simulation-commit re-forecasts each commit, add joint ResourceTimeline evaluation if needed.
  private static Optional<String> conflict(List<Occupied> occupied, PlanCandidate candidate) {
    var next = candidate.activity();
    return occupied.stream()
        .filter(
            o ->
                o.spacecraftId().equals(candidate.spacecraftId())
                    && o.activity().window().overlaps(next.window())
                    && !Collections.disjoint(
                        o.activity().exclusiveResources(), next.exclusiveResources()))
        .map(Occupied::owner)
        .findFirst();
  }
}
