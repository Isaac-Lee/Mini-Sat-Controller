package msc.domain.planning;

import static msc.domain.shared.Checks.text;

import java.util.Objects;

/** Immutable result of a PlanningRun, never an aggregate root or physical proof. */
public record FeasibilityEvaluation(Status status, String validationReference) {
  public enum Status {
    FEASIBLE,
    INFEASIBLE,
    NOT_EVALUATED
  }

  public FeasibilityEvaluation {
    Objects.requireNonNull(status);
    text(validationReference);
  }

  /** Compatibility for existing evaluated fixture/application callers. */
  public FeasibilityEvaluation(boolean feasible, String validationReference) {
    this(feasible ? Status.FEASIBLE : Status.INFEASIBLE, validationReference);
  }

  public boolean feasible() {
    return status == Status.FEASIBLE;
  }
}
