package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.*;

import java.util.Objects;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

/**
 * The pointer changes; the selected estimate never does. The publishing service owns atomic
 * compare-and-swap publication.
 */
public record OperationalDesignation(
    SpacecraftId spacecraftId,
    OrbitSolutionId orbitSolutionId,
    long version,
    MissionInstant designatedAt,
    String decisionReference) {
  public OperationalDesignation {
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(orbitSolutionId);
    positive(version);
    Objects.requireNonNull(designatedAt);
    designatedAt.requireTai();
    text(decisionReference);
  }

  public OperationalDesignation select(OrbitSolution solution, MissionInstant at, String decision) {
    return select(solution.id(), solution.spacecraftId(), at, decision);
  }

  public OperationalDesignation select(
      OrbitSolutionId solutionId, SpacecraftId owner, MissionInstant at, String decision) {
    Objects.requireNonNull(solutionId);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(at).requireTai();
    if (!spacecraftId.equals(owner)) throw new IllegalArgumentException("Wrong spacecraft");
    if (at.compareTo(designatedAt) < 0)
      throw new IllegalArgumentException("Designation time regression");
    return new OperationalDesignation(
        spacecraftId, solutionId, Math.addExact(version, 1), at, decision);
  }
}
