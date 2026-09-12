package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.OrbitSolutionId;
import msc.domain.time.TimeWindow;

/** Prediction manifest; not a measured trajectory. */
public record OrbitEphemeris(
    OrbitSolutionId solutionId,
    EstimateContext context,
    TimeWindow coverage,
    String samplesReference) {
  public OrbitEphemeris {
    Objects.requireNonNull(solutionId);
    Objects.requireNonNull(context);
    Objects.requireNonNull(coverage);
    text(samplesReference);
  }
}
