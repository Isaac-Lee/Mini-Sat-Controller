package msc.domain.flightdynamics;

import java.math.BigDecimal;
import java.util.Objects;
import msc.domain.shared.Ids.*;

public record PropellantEstimate(
    PropellantEstimateId id,
    SpacecraftId spacecraftId,
    EstimateContext context,
    BigDecimal kilograms) {
  public PropellantEstimate {
    Objects.requireNonNull(id);
    Objects.requireNonNull(spacecraftId);
    Objects.requireNonNull(context);
    if (Objects.requireNonNull(kilograms).signum() < 0)
      throw new IllegalArgumentException("Negative propellant");
    kilograms = kilograms.stripTrailingZeros();
    if (kilograms.scale() < 0) kilograms = kilograms.setScale(0);
  }
}
