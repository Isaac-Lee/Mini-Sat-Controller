package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;

/** Numerical input and predicted samples in SI units, never an operational designation. */
public final class Trajectory {
  private Trajectory() {}

  public record Vector(double x, double y, double z) {
    public Vector {
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
        throw new IllegalArgumentException("Non-finite vector");
    }
  }

  public record InitialState(
      String solutionId,
      String spacecraftId,
      MissionInstant epoch,
      Vector positionMeters,
      Vector velocityMetersPerSecond,
      String provenance) {
    public InitialState {
      text(solutionId);
      text(spacecraftId);
      Objects.requireNonNull(epoch).requireTai();
      Objects.requireNonNull(positionMeters);
      Objects.requireNonNull(velocityMetersPerSecond);
      text(provenance);
    }
  }

  public record Sample(MissionInstant time, Vector positionMeters, Vector velocityMetersPerSecond) {
    public Sample {
      Objects.requireNonNull(time).requireTai();
      Objects.requireNonNull(positionMeters);
      Objects.requireNonNull(velocityMetersPerSecond);
    }
  }

  public record Prediction(
      String solutionId,
      String model,
      String frame,
      TimeWindow coverage,
      int stepSeconds,
      List<Sample> samples) {
    public Prediction {
      text(solutionId);
      text(model);
      text(frame);
      Objects.requireNonNull(coverage);
      if (stepSeconds < 1) throw new IllegalArgumentException("Positive sampling step required");
      samples = List.copyOf(samples);
      if (samples.isEmpty()) throw new IllegalArgumentException("Empty ephemeris");
    }
  }

  public record GroundPoint(
      MissionInstant time,
      double latitudeDegrees,
      double longitudeDegrees,
      double altitudeMeters,
      String referenceDigest) {}
}
