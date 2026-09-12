package msc.domain.planning;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

public record PlanningRun(
    PlanningRunId id,
    RequestId requestId,
    MissionInstant startedAt,
    PlanningDataSnapshot inputs,
    List<Opportunity> opportunities,
    List<PlanCandidate> candidates,
    List<DecisionRecord> decisions) {
  public PlanningRun {
    Objects.requireNonNull(id);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(startedAt);
    startedAt.requireTai();
    Objects.requireNonNull(inputs);
    opportunities = List.copyOf(opportunities);
    candidates = List.copyOf(candidates);
    decisions = List.copyOf(decisions);
    if (candidates.stream()
        .anyMatch(c -> !c.runId().equals(id) || !c.requestId().equals(requestId)))
      throw new IllegalArgumentException("Candidate belongs to another run/request");
    if (candidates.stream().map(PlanCandidate::id).distinct().count() != candidates.size())
      throw new IllegalArgumentException("Duplicate candidate ID");
    var ids = candidates.stream().map(PlanCandidate::id).toList();
    if (decisions.stream().anyMatch(d -> !ids.contains(d.candidateId())))
      throw new IllegalArgumentException("Decision references absent candidate");
  }

  public record Snapshot(
      PlanningRunId id,
      RequestId requestId,
      MissionInstant startedAt,
      PlanningDataSnapshot inputs,
      List<Opportunity> opportunities,
      List<PlanCandidate.Snapshot> candidates,
      List<DecisionRecord> decisions) {
    public Snapshot {
      opportunities = List.copyOf(opportunities);
      candidates = List.copyOf(candidates);
      decisions = List.copyOf(decisions);
    }
  }

  public Snapshot snapshot() {
    return new Snapshot(
        id,
        requestId,
        startedAt,
        inputs,
        opportunities,
        candidates.stream().map(PlanCandidate::snapshot).toList(),
        decisions);
  }

  public static PlanningRun restore(
      Snapshot snapshot, BiFunction<ActivityDefinitionId, Long, ActivityDefinition> definitions) {
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(definitions);
    var candidates =
        snapshot.candidates().stream()
            .map(
                candidate ->
                    PlanCandidate.restore(
                        candidate,
                        definitions.apply(
                            candidate.activity().definitionId(),
                            candidate.activity().definitionVersion())))
            .toList();
    return new PlanningRun(
        snapshot.id(),
        snapshot.requestId(),
        snapshot.startedAt(),
        snapshot.inputs(),
        snapshot.opportunities(),
        candidates,
        snapshot.decisions());
  }
}
