package msc.services.simulator;

import java.util.*;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.monitoring.OperationalTelemetry.Frame;
import msc.domain.monitoring.OperationalTelemetry.Mode;
import msc.domain.monitoring.TelemetryObservation.Quality;
import msc.domain.time.TimeWindow;
import msc.services.simulator.SimulationCommandApi.Entry;
import msc.services.simulator.SimulatorOperationEffects.Outcome;
import msc.services.simulator.SimulatorOperationEffects.Reservoirs;

/**
 * TOY_MODEL SIMULATION resource integration and seeded record/replay. Not a validated power,
 * orbit, attitude or RF model: constant recharge (no eclipse), completion-instant operation
 * energy, and a link flag that is only caller-supplied visibility minus seeded dropouts. No link
 * budget (dB, SNR, data rate) is modeled.
 */
public final class SimulationToyPhysics {
  private SimulationToyPhysics() {}

  public static final String FIDELITY = "TOY_MODEL";
  public static final String ENVIRONMENT = "SIMULATION";

  /** Hard per-run bound on integration steps and emitted frames. */
  public static final int MAX_STEPS = 1000;

  /** Battery in watt-hours (Wh), storage in megabytes (MB), propellant in kilograms (kg). */
  public record State(double batteryWh, double storedMegabytes, double propellantKilograms) {
    public State {
      if (!Double.isFinite(batteryWh) || batteryWh < 0)
        throw new IllegalArgumentException("Battery must be finite and nonnegative Wh");
      new Reservoirs(storedMegabytes, propellantKilograms);
    }

    Reservoirs reservoirs() {
      return new Reservoirs(storedMegabytes, propellantKilograms);
    }
  }

  /**
   * Everything replay needs besides seeded stimuli. Ticks are the pinned correlation's onboard
   * ticks; visibility windows are TAI and supplied by the caller (e.g. planned contacts).
   */
  public record Setup(
      String spacecraftId,
      String source,
      long telemetryBindingVersion,
      MissionProfile mission,
      Correlation correlation,
      long fromTick,
      long toTick,
      long stepTicks,
      State initial,
      List<Entry> operations,
      List<TimeWindow> visibility,
      double linkDropoutProbability) {
    public Setup {
      Objects.requireNonNull(mission);
      Objects.requireNonNull(correlation);
      Objects.requireNonNull(initial);
      operations = List.copyOf(operations);
      visibility = List.copyOf(visibility);
      if (!source.startsWith("simulator:"))
        throw new IllegalArgumentException("Toy telemetry must identify simulation source");
      if (fromTick < 0 || toTick <= fromTick || stepTicks <= 0)
        throw new IllegalArgumentException("Need 0 <= fromTick < toTick and positive stepTicks");
      if ((toTick - fromTick + stepTicks - 1) / stepTicks > MAX_STEPS)
        throw new IllegalArgumentException("At most " + MAX_STEPS + " steps per toy run");
      if (!(linkDropoutProbability >= 0 && linkDropoutProbability <= 1))
        throw new IllegalArgumentException("Dropout probability must lie in [0, 1]");
      if (initial.batteryWh() > mission.batteryCapacityWh()
          || initial.storedMegabytes() > mission.storageCapacityMb()
          || initial.propellantKilograms() > mission.propellantKg())
        throw new IllegalArgumentException("Initial state exceeds pinned mission capacity");
      for (var op : operations)
        if (op.completionTick() <= fromTick || op.completionTick() > toTick)
          throw new IllegalArgumentException("Operation completion lies outside the toy run");
    }
  }

  /** One seeded stimulus per integration step; replay consumes these instead of the RNG. */
  public record Event(long tick, UUID frameId, boolean linkDropout) {
    public Event {
      Objects.requireNonNull(frameId);
    }
  }

  public record EventLog(
      String fidelity, String environment, long seed, Setup setup, List<Event> events) {
    public EventLog {
      events = List.copyOf(events);
      if (!FIDELITY.equals(fidelity) || !ENVIRONMENT.equals(environment))
        throw new IllegalArgumentException("Only TOY_MODEL SIMULATION logs are replayable");
    }
  }

  /** Battery-floor rejection is decided here; other outcomes come from the effects calculator. */
  public record Completion(String commandId, long tick, String outcome, double energyWh) {}

  public record Sample(
      String fidelity,
      String environment,
      long tick,
      Frame frame,
      boolean linkAvailable,
      List<Completion> completions) {}

  public static final String BATTERY_FLOOR = "BATTERY_FLOOR";

  /** Draws all randomness up front; the run is then {@link #replay} of this log. */
  public static EventLog record(Setup setup, long seed) {
    var random = new Random(seed);
    var events = new ArrayList<Event>();
    for (long tick : ticks(setup))
      events.add(
          new Event(
              tick,
              new UUID(random.nextLong(), random.nextLong()),
              random.nextDouble() < setup.linkDropoutProbability()));
    return new EventLog(FIDELITY, ENVIRONMENT, seed, setup, events);
  }

  public static List<Sample> replay(EventLog log) {
    var setup = log.setup();
    var ticks = ticks(setup);
    if (log.events().size() != ticks.size())
      throw new IllegalArgumentException("Event log does not match its setup steps");
    var mission = setup.mission();
    var c = setup.correlation();
    var ops = new ArrayList<>(setup.operations());
    ops.sort(
        Comparator.comparingLong(Entry::completionTick)
            .thenComparing(e -> e.command().id().value()));
    var state = setup.initial();
    var samples = new ArrayList<Sample>();
    long previous = setup.fromTick();
    int next = 0;
    for (int i = 0; i < ticks.size(); i++) {
      long tick = ticks.get(i);
      var event = log.events().get(i);
      if (event.tick() != tick)
        throw new IllegalArgumentException("Event log tick order does not match setup");
      // Constant recharge over the step (hours = ticks / ticksPerSecond / 3600), capped at full.
      // Credited before the step's completions, so floor decisions depend on stepTicks.
      double hours = (double) (tick - previous) / c.ticksPerSecond() / 3600.0;
      double battery =
          Math.min(
              mission.batteryCapacityWh(), state.batteryWh() + mission.rechargeWatts() * hours);
      var reservoirs = state.reservoirs();
      var completions = new ArrayList<Completion>();
      boolean floorHit = false;
      for (; next < ops.size() && ops.get(next).completionTick() <= tick; next++) {
        var op = ops.get(next);
        String id = op.command().id().value();
        // Toy: the whole operation energy is debited at completion, like the other effects.
        double energy =
            op.catalog().resources().powerWatts() * op.catalog().durationSeconds() / 3600.0;
        var effect =
            SimulatorOperationEffects.complete(mission, reservoirs, op.catalog(), op.profile());
        boolean applied = effect.outcome() == Outcome.APPLIED;
        // Only an operation that would otherwise apply can breach the floor; it then has no effect.
        if (applied && battery - energy < mission.minimumBatteryWh()) {
          floorHit = true;
          completions.add(new Completion(id, op.completionTick(), BATTERY_FLOOR, 0));
          continue;
        }
        if (applied) battery -= energy;
        reservoirs = effect.after();
        completions.add(
            new Completion(id, op.completionTick(), effect.outcome().name(), applied ? energy : 0));
      }
      state = new State(battery, reservoirs.storedMegabytes(), reservoirs.propellantKilograms());
      var at = c.tickToTai(c.timeCorrelationId(), c.clockPartition(), tick);
      boolean visible =
          setup.visibility().stream()
              .anyMatch(w -> w.start().compareTo(at) <= 0 && at.compareTo(w.end()) < 0);
      var frame =
          new Frame(
              event.frameId(),
              setup.spacecraftId(),
              setup.telemetryBindingVersion(),
              setup.source(),
              tick,
              at,
              Quality.GOOD,
              floorHit ? Mode.SAFE : Mode.NOMINAL,
              state.batteryWh(),
              state.storedMegabytes(),
              state.propellantKilograms(),
              FIDELITY + " " + ENVIRONMENT + " seed=" + log.seed() + "; not hardware telemetry");
      samples.add(
          new Sample(
              FIDELITY, ENVIRONMENT, tick, frame, visible && !event.linkDropout(), completions));
      previous = tick;
    }
    return samples;
  }

  /** Step end ticks; the last step is shortened to land exactly on toTick. */
  private static List<Long> ticks(Setup setup) {
    var ticks = new ArrayList<Long>();
    for (long t = setup.fromTick(); t < setup.toTick(); )
      ticks.add(t += Math.min(setup.stepTicks(), setup.toTick() - t));
    return ticks;
  }
}
