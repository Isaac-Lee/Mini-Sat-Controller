package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import msc.domain.flightdynamics.RestToRestSlew;
import msc.domain.shared.Checks;
import msc.domain.time.MissionInstant;

/**
 * Kinematic rest-to-rest maneuver estimate between consecutive required-target-pointing samples.
 * The slew angle is the angle between the two EME2000 line-of-sight unit vectors (boresight
 * direction only; rotation about the boresight is not modeled). Each endpoint is treated as
 * inertially at rest, so target-tracking motion during and after the slew is ignored. Angles in
 * degrees, rates in deg/s, accelerations in deg/s², times in seconds, instants in TAI.
 *
 * <p>This is not attitude determination, attitude control, a commanded attitude, or a claim that
 * the spacecraft can track or settle on the target; see ADR-0015.
 */
public final class ManeuverContracts {
  private ManeuverContracts() {}

  public static final String ENVIRONMENT = "SIMULATION";
  public static final String FRAME = "EME2000_LINE_OF_SIGHT";
  public static final String MODEL = "rest-to-rest-bang-coast-bang-kinematic-v1";

  /** One stored required-target-pointing result and the exact sample instant to point at. */
  public record PointingTarget(String requiredTargetPointingId, MissionInstant instant) {
    public PointingTarget {
      text(requiredTargetPointingId);
      Objects.requireNonNull(instant).requireTai();
    }
  }

  /**
   * {@code agilityModelVersion} pins the approved mission-definition agility model, which supplies
   * the maximum rate, settling time and off-nadir limit. The approved model has no acceleration
   * limit, so {@code maximumAccelerationDegreesPerSecondSquared} is an explicit caller-supplied
   * simulation assumption, recorded on the result.
   */
  public record Request(
      String spacecraftId,
      long agilityModelVersion,
      double maximumAccelerationDegreesPerSecondSquared,
      List<PointingTarget> targets) {
    public Request {
      text(spacecraftId);
      Checks.positive(agilityModelVersion);
      if (!Double.isFinite(maximumAccelerationDegreesPerSecondSquared)
          || maximumAccelerationDegreesPerSecondSquared <= 0
          || maximumAccelerationDegreesPerSecondSquared > 180)
        throw new IllegalArgumentException("Maximum acceleration must be in (0, 180] deg/s²");
      targets = List.copyOf(targets);
      if (targets.size() < 2 || targets.size() > 256)
        throw new IllegalArgumentException("A maneuver plan needs 2..256 targets");
      for (int i = 1; i < targets.size(); i++)
        if (targets.get(i).instant().compareTo(targets.get(i - 1).instant()) <= 0)
          throw new IllegalArgumentException("Target instants must strictly increase");
    }
  }

  public enum Infeasibility {
    /** Slew plus settling exceeds the time between the two sample instants. */
    INSUFFICIENT_TIME,
    /** An endpoint's required off-nadir exceeds the approved agility limit. */
    OFF_NADIR_LIMIT_EXCEEDED,
    /** An endpoint sample records the target below the elevation mask. */
    TARGET_NOT_VISIBLE
  }

  public record Transition(
      int fromIndex,
      int toIndex,
      double slewAngleDegrees,
      RestToRestSlew.Shape shape,
      double peakRateDegreesPerSecond,
      double slewSeconds,
      double settlingSeconds,
      double requiredSeconds,
      double availableSeconds,
      boolean feasible,
      Set<Infeasibility> infeasibilities) {
    public Transition {
      Objects.requireNonNull(shape);
      infeasibilities = Checks.orderedEnumSet(Infeasibility.class, infeasibilities);
      if (feasible != infeasibilities.isEmpty())
        throw new IllegalArgumentException("Feasibility must match recorded infeasibilities");
    }
  }

  public enum Scope {
    KINEMATIC_MODEL_NO_DYNAMICS
  }

  public record Plan(
      String spacecraftId,
      String environment,
      String frame,
      String model,
      long agilityModelVersion,
      AgilityContracts.Model agility,
      double maximumAccelerationDegreesPerSecondSquared,
      List<PointingTarget> targets,
      List<Transition> transitions,
      boolean feasible,
      Scope scope) {
    public Plan {
      text(spacecraftId);
      if (!ENVIRONMENT.equals(environment))
        throw new IllegalArgumentException("Maneuver estimates are simulation only");
      text(frame);
      text(model);
      Objects.requireNonNull(agility);
      targets = List.copyOf(targets);
      transitions = List.copyOf(transitions);
      if (transitions.size() != targets.size() - 1)
        throw new IllegalArgumentException("One transition per consecutive target pair");
      if (feasible != transitions.stream().allMatch(Transition::feasible))
        throw new IllegalArgumentException("Plan feasibility must match its transitions");
      Objects.requireNonNull(scope);
    }
  }
}
