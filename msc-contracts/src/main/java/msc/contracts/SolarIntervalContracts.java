package msc.contracts;

import static msc.domain.shared.Checks.text;
import java.util.Objects;
import msc.contracts.IlluminationContracts.Aoi;
import msc.domain.time.TimeWindow;

/** Owner-declared simulation assumptions, not measured hardware or ephemeris qualifications. */
public final class SolarIntervalContracts {
  private SolarIntervalContracts() {}

  public record Assumptions(String spacecraftId, String missionDefinitionVersion,
      String environment, String solarModel, String referenceDigest, TimeWindow validInterval,
      Aoi extent, double maximumRateRadiansPerSecond, double evaluationErrorRadians,
      double maximumStepSeconds, String justificationReference) {
    public Assumptions {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(solarModel);
      text(justificationReference);
      Objects.requireNonNull(validInterval);
      Objects.requireNonNull(extent);
      if (!"SIMULATION".equals(environment) || referenceDigest == null
          || !referenceDigest.matches("[a-f0-9]{64}")
          || !Double.isFinite(maximumRateRadiansPerSecond) || maximumRateRadiansPerSecond < 0
          || !Double.isFinite(evaluationErrorRadians) || evaluationErrorRadians <= 0
          || !Double.isFinite(maximumStepSeconds) || maximumStepSeconds <= 0 || maximumStepSeconds > 60)
        throw new IllegalArgumentException("Explicit bounded simulation solar assumptions required");
    }

    /** Assumptions apply to all subrectangles inside extent at its exact geodetic altitude. */
    public void requireCoverage(String model, String digest, Aoi target, TimeWindow horizon) {
      Objects.requireNonNull(target);
      Objects.requireNonNull(horizon);
      if (!solarModel.equals(model) || !referenceDigest.equals(digest)
          || !validInterval.contains(horizon)
          || target.westLongitudeDegrees() < extent.westLongitudeDegrees()
          || target.eastLongitudeDegrees() > extent.eastLongitudeDegrees()
          || target.southLatitudeDegrees() < extent.southLatitudeDegrees()
          || target.northLatitudeDegrees() > extent.northLatitudeDegrees()
          || Double.compare(target.altitudeMeters(), extent.altitudeMeters()) != 0)
        throw new IllegalArgumentException("Query is outside the solar assumption model/time/area scope");
    }
  }

  public record Publish(long expectedVersion, Assumptions assumptions) {
    public Publish {
      if (expectedVersion < 0) throw new IllegalArgumentException("Nonnegative version required");
      Objects.requireNonNull(assumptions);
    }
  }
}
