package msc.domain.planning;

import java.util.Objects;
import msc.domain.shared.Ids.*;

/** Schedule-owned references; never embeds the user's request aggregate. */
public record Assignment(
    AssignmentId id,
    CandidateId candidateId,
    RequestId requestId,
    PlanningRunId runId,
    ActivityId activityId) {
  public Assignment {
    Objects.requireNonNull(id);
    Objects.requireNonNull(candidateId);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(activityId);
  }
}
