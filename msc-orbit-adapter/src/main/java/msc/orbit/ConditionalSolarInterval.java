package msc.orbit;

import java.util.*;
import java.util.function.DoubleUnaryOperator;

/** Temporal lower bounds conditional on an independently justified rate/error assumption. */
public final class ConditionalSolarInterval {
  private ConditionalSolarInterval() {}

  public record Assumptions(double maximumRateRadiansPerSecond, double evaluationErrorRadians,
      double maximumStepSeconds, String reference) {
    public Assumptions {
      if (!Double.isFinite(maximumRateRadiansPerSecond) || maximumRateRadiansPerSecond < 0
          || !Double.isFinite(evaluationErrorRadians) || evaluationErrorRadians <= 0
          || !Double.isFinite(maximumStepSeconds) || maximumStepSeconds <= 0 || maximumStepSeconds > 60
          || reference == null || reference.isBlank())
        throw new IllegalArgumentException("Explicit finite rate, positive error/step and reference required");
    }
  }

  public record Cell(double startSeconds, double endSeconds, double sampleSeconds,
      double sampledLowerElevationRadians, double intervalLowerElevationRadians) {}

  public record Result(double durationSeconds, Assumptions assumptions, List<Cell> cells,
      double lowerElevationRadians) {
    public Result { cells = List.copyOf(cells); }
  }

  /**
   * If |f(t)-f(s)| <= L|t-s| and sample overestimation <= E, then
   * f(t) >= sampled(f(mid)) - E - L * max(mid-start,end-mid) throughout each cell.
   * Samples never establish L or E; they must come from an independent model assumption.
   */
  public static Result evaluate(double durationSeconds, Assumptions assumptions,
      DoubleUnaryOperator spatialLowerElevation) {
    Objects.requireNonNull(assumptions);
    Objects.requireNonNull(spatialLowerElevation);
    if (!Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 86400)
      throw new IllegalArgumentException("Positive interval no longer than 24 hours required");
    double count = Math.ceil(durationSeconds / assumptions.maximumStepSeconds());
    if (count > 4096) throw new IllegalArgumentException("Interval exceeds 4096-sample budget");
    int cells = Math.max(1, (int) count);
    var result = new ArrayList<Cell>(cells);
    double lower = Double.POSITIVE_INFINITY;
    double start = 0;
    for (int i = 0; i < cells; i++) {
      double end = i == cells - 1 ? durationSeconds : durationSeconds * ((i + 1.0) / cells);
      double middle = start + (end - start) / 2;
      double sample = spatialLowerElevation.applyAsDouble(middle);
      if (!Double.isFinite(sample) || sample < -Math.PI / 2 || sample > Math.PI / 2)
        throw new IllegalArgumentException("Finite solar elevation in [-pi/2,pi/2] required");
      double distance = Math.nextUp(Math.max(middle - start, end - middle));
      double change = Math.nextUp(assumptions.maximumRateRadiansPerSecond() * distance);
      double bound = Math.nextDown(Math.nextDown(sample - assumptions.evaluationErrorRadians()) - change);
      if (!Double.isFinite(bound)) throw new IllegalArgumentException("Unrepresentable interval bound");
      result.add(new Cell(start, end, middle, sample, bound));
      lower = Math.min(lower, bound);
      start = end;
    }
    return new Result(durationSeconds, assumptions, result, lower);
  }
}
