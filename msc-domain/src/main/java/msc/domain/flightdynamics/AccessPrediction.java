package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import msc.domain.time.TimeWindow;

/**
 * Geometric point access only; not AOI coverage, weather feasibility, booking or command authority.
 */
public record AccessPrediction(
    String solutionId,
    String spacecraftId,
    String model,
    String referenceDigest,
    Query query,
    double rootToleranceSeconds,
    double maximumCheckSeconds,
    List<TimeWindow> windows) {
  public AccessPrediction {
    text(solutionId);
    text(spacecraftId);
    text(model);
    text(referenceDigest);
    Objects.requireNonNull(query);
    windows = List.copyOf(windows);
  }

  public enum Kind {
    GROUND_CONTACT,
    POINT_IMAGING
  }

  public record Target(
      String id, double latitudeDegrees, double longitudeDegrees, double altitudeMeters) {
    public Target {
      text(id);
      if (!Double.isFinite(latitudeDegrees)
          || Math.abs(latitudeDegrees) > 90
          || !Double.isFinite(longitudeDegrees)
          || Math.abs(longitudeDegrees) > 180
          || !Double.isFinite(altitudeMeters)
          || altitudeMeters < -500
          || altitudeMeters > 10000)
        throw new IllegalArgumentException("Invalid terrestrial target coordinates");
    }
  }

  public record Query(
      Kind kind,
      Target target,
      TimeWindow horizon,
      double minimumElevationDegrees,
      double maximumOffNadirDegrees,
      int minimumDurationSeconds) {
    public Query {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(target);
      Objects.requireNonNull(horizon);
      if (!Double.isFinite(minimumElevationDegrees)
          || minimumElevationDegrees < 0
          || minimumElevationDegrees >= 90)
        throw new IllegalArgumentException("Elevation mask must be 0..<90 degrees");
      if (!Double.isFinite(maximumOffNadirDegrees)
          || maximumOffNadirDegrees <= 0
          || maximumOffNadirDegrees > 60)
        throw new IllegalArgumentException("Imaging off-nadir limit must be >0..60 degrees");
      if (minimumDurationSeconds < 1 || minimumDurationSeconds > 3600)
        throw new IllegalArgumentException("Access duration must be 1..3600 seconds");
    }
  }
}
