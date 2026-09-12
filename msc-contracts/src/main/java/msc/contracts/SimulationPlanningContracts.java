package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.anomaly.MissionPhase;

/**
 * Explicit ADMIN-published simulation planning model, bound to a mission definition. Supplies
 * mission phase/mode and simulation-only sensor/power assumptions still missing from {@link
 * CatalogContracts.MissionProfile} that later full feasibility evaluation needs. Never inferred
 * from NORAD identity or public orbit elements: SPACEEYE-T1 / NORAD 63229 is public GP orbit
 * tracking only, never a hardware telemetry or instrument source. No implicit clear-sky, solar
 * power or free-resource fallback is expressed here; every value is an explicit simulation
 * assumption.
 */
public final class SimulationPlanningContracts {
  private SimulationPlanningContracts() {}

  /**
   * @param worstCaseSunlitFraction An aggregate illumination assumption, not a pointwise supply
   *     guarantee. It has no time interval or eclipse ordering and MUST NOT be converted into
   *     constant generation for battery feasibility. Until a pinned time-resolved illumination
   *     forecast exists, conservativeSupply uses the lower of the two explicit generation bounds
   *     throughout the horizon. Approval does not make an averaged supply physically conservative.
   */
  public record Model(
      String spacecraftId,
      String missionDefinitionVersion,
      String environment,
      MissionPhase phase,
      String mode,
      double swathWidthMeters,
      double groundSampleDistanceMeters,
      double maximumOffNadirDegrees,
      double minimumSunElevationDegrees,
      boolean requiresWeatherEvaluation,
      double busDrawWatts,
      double sunlitGenerationWatts,
      double eclipseGenerationWatts,
      double worstCaseSunlitFraction,
      double minimumPropellantKg,
      String approvalReference) {
    public Model {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(mode);
      text(approvalReference);
      Objects.requireNonNull(phase, "Mission phase is required");
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException(
            "Only explicitly simulated planning models are supported");
      if (!Double.isFinite(swathWidthMeters)
          || swathWidthMeters <= 0
          || swathWidthMeters > 1_000_000)
        throw new IllegalArgumentException("Invalid swath width");
      if (!Double.isFinite(groundSampleDistanceMeters)
          || groundSampleDistanceMeters <= 0
          || groundSampleDistanceMeters > 10_000)
        throw new IllegalArgumentException("Invalid ground sample distance");
      if (!Double.isFinite(maximumOffNadirDegrees)
          || maximumOffNadirDegrees <= 0
          || maximumOffNadirDegrees > 60)
        throw new IllegalArgumentException("Invalid maximum off-nadir bound");
      if (!Double.isFinite(minimumSunElevationDegrees)
          || minimumSunElevationDegrees < 0
          || minimumSunElevationDegrees >= 90)
        throw new IllegalArgumentException("Invalid minimum sun elevation bound");
      // There is to be no implicit clear-sky path: every simulation planning model must require
      // an explicit weather evaluation gate downstream.
      if (!requiresWeatherEvaluation)
        throw new IllegalArgumentException(
            "Simulation planning models must require weather evaluation");
      if (!Double.isFinite(busDrawWatts) || busDrawWatts < 0)
        throw new IllegalArgumentException("Invalid bus draw");
      if (!Double.isFinite(sunlitGenerationWatts) || sunlitGenerationWatts < 0)
        throw new IllegalArgumentException("Invalid sunlit generation");
      if (!Double.isFinite(eclipseGenerationWatts) || eclipseGenerationWatts < 0)
        throw new IllegalArgumentException("Invalid eclipse generation");
      if (eclipseGenerationWatts > sunlitGenerationWatts)
        throw new IllegalArgumentException(
            "Eclipse generation cannot exceed sunlit generation in a conservative model");
      if (!Double.isFinite(worstCaseSunlitFraction)
          || worstCaseSunlitFraction < 0
          || worstCaseSunlitFraction > 1)
        throw new IllegalArgumentException("Invalid worst-case sunlit fraction");
      if (!Double.isFinite(minimumPropellantKg) || minimumPropellantKg < 0)
        throw new IllegalArgumentException("Invalid minimum propellant reserve");
    }

    /** Conservative simulation supply when the ordering of sunlit/eclipse intervals is unknown. */
    public msc.domain.planning.ResourceTimeline.Supply conservativeSupply(
        msc.domain.time.TimeWindow horizon, String modelVersionReference) {
      return new msc.domain.planning.ResourceTimeline.Supply(
          horizon, eclipseGenerationWatts, busDrawWatts, modelVersionReference);
    }
  }

  public record Publish(long expectedVersion, Model model) {
    public Publish {
      if (expectedVersion < 0 || model == null)
        throw new IllegalArgumentException("Expected version and model are required");
    }
  }
}
