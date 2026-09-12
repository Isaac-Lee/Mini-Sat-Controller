package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.*;

public record OrbitSolution(
    OrbitSolutionId id,
    SpacecraftId spacecraftId,
    EstimateContext context,
    String stateVectorReference) {
  public OrbitSolution {
    Objects.requireNonNull(id);
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(context);
    text(stateVectorReference);
  }
}
