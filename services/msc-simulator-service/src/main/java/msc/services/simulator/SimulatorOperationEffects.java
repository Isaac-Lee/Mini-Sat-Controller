package msc.services.simulator;

import java.util.Objects;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.OperationResourceContracts.Operation;
import msc.contracts.OperationResourceContracts.OperationResourceProfile;

/**
 * Deterministic SIMULATION completion effects. The future durable executor must persist the result
 * with its command ledger in one transaction. This calculator does not authorize or execute a
 * command, advance a clock, deliver data, or model continuous battery/attitude dynamics.
 */
public final class SimulatorOperationEffects {
  private SimulatorOperationEffects() {}

  public record Reservoirs(double storedMegabytes, double propellantKilograms) {
    public Reservoirs {
      if (!Double.isFinite(storedMegabytes)
          || storedMegabytes < 0
          || !Double.isFinite(propellantKilograms)
          || propellantKilograms < 0)
        throw new IllegalArgumentException("Simulation reservoirs must be finite and nonnegative");
    }
  }

  public enum Outcome {
    APPLIED,
    NOT_SUPPORTED,
    STORAGE_CAPACITY_EXCEEDED,
    INSUFFICIENT_PROPELLANT,
    NONFINITE_EFFECT
  }

  /**
   * Drain is modeled onboard accounting, never evidence of ground reception or payload deletion.
   */
  public record Result(
      Outcome outcome,
      Reservoirs before,
      Reservoirs after,
      double generatedMegabytes,
      double attemptedDrainMegabytes,
      double actualDrainMegabytes,
      double consumedPropellantKilograms,
      double declaredPowerWatts) {}

  /** Call only with a mission and profile resolved from the executor's exact pinned evidence. */
  public static Result complete(
      MissionProfile mission,
      Reservoirs before,
      CatalogEntry catalog,
      OperationResourceProfile profile) {
    Objects.requireNonNull(mission);
    Objects.requireNonNull(before);
    Objects.requireNonNull(catalog);
    Objects.requireNonNull(profile);
    if (before.storedMegabytes() > mission.storageCapacityMb()
        || before.propellantKilograms() > mission.propellantKg())
      throw new IllegalArgumentException("Initial reservoirs exceed pinned mission capacity");
    var expected = profile.expected();
    var resources = catalog.resources();
    if (!profile.catalog().catalogId().equals(catalog.id())
        || profile.catalog().catalogVersion() != catalog.version()
        || !profile.operation().name().equals(catalog.template().operation())
        || Double.compare(expected.powerWatts(), resources.powerWatts()) != 0
        || Double.compare(expected.generatedMegabytes(), resources.generatedMegabytes()) != 0
        || Double.compare(expected.propellantKilograms(), resources.propellantKilograms()) != 0)
      throw new IllegalArgumentException("Operation profile does not match pinned catalog");
    if (profile.operation() != Operation.IMAGE && profile.operation() != Operation.DOWNLINK)
      return rejected(Outcome.NOT_SUPPORTED, before, resources.powerWatts());

    double peak = before.storedMegabytes() + resources.generatedMegabytes();
    double drain =
        profile.operation() == Operation.DOWNLINK
            ? profile.downlinkMegabytesPerSecond() * catalog.durationSeconds()
            : 0;
    if (!Double.isFinite(peak) || !Double.isFinite(drain))
      return rejected(Outcome.NONFINITE_EFFECT, before, resources.powerWatts());
    // Defined checkpoint of this atomic-completion model, not a continuous peak estimate.
    if (peak > mission.storageCapacityMb())
      return rejected(Outcome.STORAGE_CAPACITY_EXCEEDED, before, resources.powerWatts());
    if (resources.propellantKilograms() > before.propellantKilograms())
      return rejected(Outcome.INSUFFICIENT_PROPELLANT, before, resources.powerWatts());
    double actualDrain = Math.min(peak, drain);
    var after =
        new Reservoirs(
            peak - actualDrain, before.propellantKilograms() - resources.propellantKilograms());
    return new Result(
        Outcome.APPLIED,
        before,
        after,
        resources.generatedMegabytes(),
        drain,
        actualDrain,
        resources.propellantKilograms(),
        resources.powerWatts());
  }

  private static Result rejected(Outcome reason, Reservoirs before, double powerWatts) {
    return new Result(reason, before, before, 0, 0, 0, 0, powerWatts);
  }
}
