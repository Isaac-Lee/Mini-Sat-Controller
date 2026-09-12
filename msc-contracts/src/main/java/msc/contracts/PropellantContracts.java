package msc.contracts;

import static msc.domain.shared.Checks.*;

import java.math.BigDecimal;
import java.util.Objects;
import msc.domain.flightdynamics.PropellantEstimate;
import msc.domain.monitoring.OperationalTelemetry.Estimate;
import msc.domain.time.*;

public final class PropellantContracts {
  private PropellantContracts() {}

  /** Approved synthetic mass-observation model; not a pressure/temperature tank model. */
  public record Model(
      String spacecraftId,
      String missionDefinitionVersion,
      String environment,
      long telemetryBindingVersion,
      BigDecimal absoluteUncertaintyKg,
      BigDecimal maximumUnmodeledLossKgPerSecond,
      int maximumPropagationSeconds,
      String approvalReference) {
    public Model {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(approvalReference);
      positive(telemetryBindingVersion);
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException("Only explicit simulation mass models are supported");
      if (Objects.requireNonNull(absoluteUncertaintyKg).signum() < 0
          || Objects.requireNonNull(maximumUnmodeledLossKgPerSecond).signum() < 0
          || maximumPropagationSeconds < 1
          || maximumPropagationSeconds > 172800)
        throw new IllegalArgumentException(
            "Invalid propellant uncertainty, loss bound or propagation horizon");
      absoluteUncertaintyKg = absoluteUncertaintyKg.stripTrailingZeros();
      if (absoluteUncertaintyKg.scale() < 0) absoluteUncertaintyKg = absoluteUncertaintyKg.setScale(0);
      maximumUnmodeledLossKgPerSecond = maximumUnmodeledLossKgPerSecond.stripTrailingZeros();
      if (maximumUnmodeledLossKgPerSecond.scale() < 0)
        maximumUnmodeledLossKgPerSecond = maximumUnmodeledLossKgPerSecond.setScale(0);
    }
  }

  public record Publish(long expectedVersion, Model model) {
    public Publish {
      if (expectedVersion < 0 || model == null)
        throw new IllegalArgumentException("Expected version and model required");
    }
  }

  public record Query(String spacecraftId, long telemetryVersion, long modelVersion) {
    public Query {
      text(spacecraftId);
      positive(telemetryVersion);
      positive(modelVersion);
    }
  }

  public record Snapshot(
      String id,
      PropellantEstimate estimate,
      long telemetryVersion,
      Estimate source,
      long modelVersion,
      Model model,
      MissionInstant capturedAt,
      MissionInstant validUntil) {
    public BigDecimal lowerBoundAt(MissionInstant time) {
      var epoch = estimate.context().epoch();
      if (time.compareTo(epoch) < 0 || time.compareTo(validUntil) > 0)
        throw new IllegalArgumentException("Time outside propellant model propagation validity");
      var elapsed =
          BigDecimal.valueOf(Math.subtractExact(time.seconds(), epoch.seconds()))
              .add(BigDecimal.valueOf((long) time.nanos() - epoch.nanos(), 9));
      return estimate
          .kilograms()
          .subtract(model.absoluteUncertaintyKg())
          .subtract(elapsed.multiply(model.maximumUnmodeledLossKgPerSecond()))
          .max(BigDecimal.ZERO);
    }
  }
}
