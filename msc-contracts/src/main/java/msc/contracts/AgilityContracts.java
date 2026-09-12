package msc.contracts;

import static msc.domain.shared.Checks.text;

/** Explicit simulation parameters, never inferred from NORAD identity or public orbit elements. */
public final class AgilityContracts {
  private AgilityContracts() {}

  public record Model(
      String spacecraftId,
      String missionDefinitionVersion,
      String environment,
      double maximumOffNadirDegrees,
      double slewRateDegreesPerSecond,
      double settlingSeconds,
      String approvalReference) {
    public Model {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(approvalReference);
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException(
            "Only explicitly simulated agility models are supported");
      if (!Double.isFinite(maximumOffNadirDegrees)
          || maximumOffNadirDegrees <= 0
          || maximumOffNadirDegrees > 60
          || !Double.isFinite(slewRateDegreesPerSecond)
          || slewRateDegreesPerSecond <= 0
          || slewRateDegreesPerSecond > 180
          || !Double.isFinite(settlingSeconds)
          || settlingSeconds < 0
          || settlingSeconds > 3600)
        throw new IllegalArgumentException("Invalid bounded agility parameters");
    }
  }

  public record Publish(long expectedVersion, Model model) {
    public Publish {
      if (expectedVersion < 0 || model == null)
        throw new IllegalArgumentException("Expected version and model are required");
    }
  }
}
