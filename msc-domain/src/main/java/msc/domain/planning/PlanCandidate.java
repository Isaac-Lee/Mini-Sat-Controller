package msc.domain.planning;

import java.util.Objects;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.shared.Ids.*;
import msc.domain.time.TimeWindow;

/** Immutable approved-activity proposal; creation does not commit an assignment. */
public final class PlanCandidate {
  private final CandidateId id;
  private final PlanningRunId runId;
  private final RequestId requestId;
  private final SpacecraftId spacecraftId;
  private final ScheduledActivity activity;
  private final FeasibilityEvaluation feasibility;
  private final MissionPhase phase;
  private final String mode;

  private PlanCandidate(
      CandidateId id,
      PlanningRunId runId,
      RequestId requestId,
      SpacecraftId spacecraftId,
      ScheduledActivity activity,
      FeasibilityEvaluation feasibility,
      MissionPhase phase,
      String mode) {
    this.id = Objects.requireNonNull(id);
    this.runId = Objects.requireNonNull(runId);
    this.requestId = Objects.requireNonNull(requestId);
    this.spacecraftId = Objects.requireNonNull(spacecraftId);
    this.activity = Objects.requireNonNull(activity);
    this.feasibility = Objects.requireNonNull(feasibility);
    this.phase = Objects.requireNonNull(phase);
    this.mode = msc.domain.shared.Checks.text(mode);
  }

  public static PlanCandidate propose(
      CandidateId id,
      PlanningRunId runId,
      RequestId requestId,
      SpacecraftId spacecraftId,
      ActivityId activityId,
      TimeWindow window,
      ActivityDefinition definition,
      MissionPhase phase,
      String mode,
      FeasibilityEvaluation feasibility) {
    definition.requireSchedulable(phase, mode);
    return new PlanCandidate(
        id,
        runId,
        requestId,
        spacecraftId,
        new ScheduledActivity(
            activityId,
            definition.id(),
            definition.version(),
            window,
            definition.exclusiveResources()),
        feasibility,
        phase,
        mode);
  }

  /** Serializable values; reconstruct only with the exact approved catalog definition. */
  public record Snapshot(
      CandidateId id,
      PlanningRunId runId,
      RequestId requestId,
      SpacecraftId spacecraftId,
      ScheduledActivity activity,
      FeasibilityEvaluation feasibility,
      MissionPhase phase,
      String mode) {}

  public Snapshot snapshot() {
    return new Snapshot(id, runId, requestId, spacecraftId, activity, feasibility, phase, mode);
  }

  public static PlanCandidate restore(Snapshot snapshot, ActivityDefinition definition) {
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(definition);
    var activity = Objects.requireNonNull(snapshot.activity());
    if (!activity.definitionId().equals(definition.id())
        || activity.definitionVersion() != definition.version()
        || !activity.exclusiveResources().equals(definition.exclusiveResources()))
      throw new IllegalArgumentException("Candidate does not match the pinned activity definition");
    return propose(
        snapshot.id(),
        snapshot.runId(),
        snapshot.requestId(),
        snapshot.spacecraftId(),
        activity.id(),
        activity.window(),
        definition,
        snapshot.phase(),
        snapshot.mode(),
        snapshot.feasibility());
  }

  public CandidateId id() {
    return id;
  }

  public PlanningRunId runId() {
    return runId;
  }

  public RequestId requestId() {
    return requestId;
  }

  public SpacecraftId spacecraftId() {
    return spacecraftId;
  }

  public ScheduledActivity activity() {
    return activity;
  }

  public FeasibilityEvaluation feasibility() {
    return feasibility;
  }
}
