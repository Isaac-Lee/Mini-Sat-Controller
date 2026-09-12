package msc.domain.acquisition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import msc.domain.shared.Ids.*;
import msc.domain.spacecraftcontrol.ExecutionOutcome;

public record Acquisition(
    AcquisitionId id,
    RequestId requestId,
    AssignmentId assignmentId,
    ActivityId activityId,
    Optional<CommandLoadId> commandLoadId,
    ExecutionOutcome executionBelief,
    List<ReceptionId> receptions,
    Completeness completeness,
    List<ProductId> l0Products) {
  public enum Completeness {
    UNKNOWN,
    INCOMPLETE,
    COMPLETE
  }

  public Acquisition {
    Objects.requireNonNull(id);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(assignmentId);
    Objects.requireNonNull(activityId);
    Objects.requireNonNull(commandLoadId);
    Objects.requireNonNull(executionBelief);
    receptions = List.copyOf(receptions);
    Objects.requireNonNull(completeness);
    l0Products = List.copyOf(l0Products);
  }
}
