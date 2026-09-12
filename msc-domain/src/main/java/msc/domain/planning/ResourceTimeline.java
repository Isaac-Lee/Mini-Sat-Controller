package msc.domain.planning;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.time.*;

/** Piecewise-linear reservoir forecast. Inputs are estimates; output is not telemetry truth. */
public final class ResourceTimeline {
  private ResourceTimeline() {}

  public enum Resource {
    BATTERY,
    STORAGE,
    PROPELLANT,
    THERMAL,
    WHEEL_MOMENTUM
  }

  public record Limits(
      double batteryCapacityWh,
      double minimumBatteryWh,
      double storageCapacityMb,
      double minimumPropellantKg) {
    public Limits {
      finite(batteryCapacityWh, minimumBatteryWh, storageCapacityMb, minimumPropellantKg);
      if (batteryCapacityWh <= minimumBatteryWh || storageCapacityMb <= 0)
        throw new IllegalArgumentException("Invalid reservoir limits");
    }
  }

  public record Initial(
      MissionInstant at,
      double batteryWh,
      double storedMb,
      double propellantKg,
      String evidenceReference) {
    public Initial {
      Objects.requireNonNull(at).requireTai();
      finite(batteryWh, storedMb, propellantKg);
      text(evidenceReference);
    }
  }

  public record Supply(
      TimeWindow window, double generationWatts, double busDrawWatts, String reference) {
    public Supply {
      Objects.requireNonNull(window);
      finite(generationWatts, busDrawWatts);
      text(reference);
    }
  }

  public record Load(
      String activityId,
      TimeWindow window,
      double drawWatts,
      double generatedMbPerSecond,
      double downlinkedMbPerSecond,
      double propellantKg) {
    public Load {
      text(activityId);
      Objects.requireNonNull(window);
      finite(drawWatts, generatedMbPerSecond, downlinkedMbPerSecond, propellantKg);
    }
  }

  public record Sample(MissionInstant at, double batteryWh, double storedMb, double propellantKg) {}

  public record Violation(MissionInstant at, Resource resource, String reason) {}

  public record Result(
      ResourceValidation.Status status,
      String initialReference,
      String modelReference,
      Set<Resource> modeledResources,
      List<Sample> trajectory,
      List<Violation> violations) {
    public Result {
      // Stable API array order across JVMs; Set.copyOf iteration is process-randomized.
      var orderedResources = EnumSet.noneOf(Resource.class);
      orderedResources.addAll(modeledResources);
      modeledResources = Collections.unmodifiableSet(orderedResources);
      trajectory = List.copyOf(trajectory);
      violations = List.copyOf(violations);
    }
  }

  private static void finite(double... values) {
    for (double value : values)
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Finite nonnegative resource value required");
  }

  private static double seconds(MissionInstant from, MissionInstant to) {
    return Math.subtractExact(to.seconds(), from.seconds())
        + (to.nanos() - from.nanos()) / 1_000_000_000.0;
  }

  /** Bind every resource load to the exact committed/proposed schedule activity set. */
  public static Result evaluate(
      MissionSchedule schedule,
      Initial initial,
      Limits limits,
      List<Supply> supply,
      List<Load> loads,
      Set<Resource> required,
      String modelReference) {
    var byId = new HashMap<String, Load>();
    for (var load : loads)
      if (byId.put(load.activityId(), load) != null)
        throw new IllegalArgumentException("Duplicate resource load");
    if (byId.size() != schedule.activities().size()
        || schedule.activities().stream()
            .anyMatch(
                activity ->
                    !byId.containsKey(activity.id().value())
                        || !byId.get(activity.id().value()).window().equals(activity.window())))
      return incomplete(
          initial,
          modelReference,
          Set.of(Resource.BATTERY, Resource.STORAGE, Resource.PROPELLANT),
          "Resource profiles must cover the exact schedule activity IDs and windows");
    return evaluate(
        schedule.key().horizon(), initial, limits, supply, loads, required, modelReference);
  }

  public static Result evaluate(
      TimeWindow horizon,
      Initial initial,
      Limits limits,
      List<Supply> supply,
      List<Load> loads,
      Set<Resource> required,
      String modelReference) {
    Objects.requireNonNull(horizon);
    Objects.requireNonNull(initial);
    Objects.requireNonNull(limits);
    text(modelReference);
    supply = List.copyOf(supply);
    loads = List.copyOf(loads);
    required = Set.copyOf(required);
    var modeled = Set.of(Resource.BATTERY, Resource.STORAGE, Resource.PROPELLANT);
    var violations = new ArrayList<Violation>();
    var samples = new ArrayList<Sample>();
    if (!initial.at().equals(horizon.start()))
      throw new IllegalArgumentException("Initial estimate must be anchored at the horizon start");
    if (seconds(horizon.start(), horizon.end()) > 7 * 86400
        || loads.size() > 10000
        || supply.size() > 10000)
      throw new IllegalArgumentException("Timeline exceeds bounded validation workload");
    for (var resource : required)
      if (!modeled.contains(resource))
        violations.add(
            new Violation(horizon.start(), resource, "Required resource model is unavailable"));
    if (!violations.isEmpty())
      return new Result(
          ResourceValidation.Status.NOT_EVALUATED,
          initial.evidenceReference(),
          modelReference,
          modeled,
          samples,
          violations);
    var ordered = supply.stream().sorted(Comparator.comparing(s -> s.window().start())).toList();
    MissionInstant cursor = horizon.start();
    for (var part : ordered) {
      if (!horizon.contains(part.window()) || !part.window().start().equals(cursor))
        return incomplete(
            initial,
            modelReference,
            modeled,
            "Supply forecast must cover the horizon exactly, without gaps or overlap");
      cursor = part.window().end();
    }
    if (!cursor.equals(horizon.end()))
      return incomplete(
          initial, modelReference, modeled, "Supply forecast does not cover the entire horizon");
    var ids = new HashSet<String>();
    var boundaries = new TreeSet<MissionInstant>();
    boundaries.add(horizon.start());
    boundaries.add(horizon.end());
    for (var part : ordered) {
      boundaries.add(part.window().start());
      boundaries.add(part.window().end());
    }
    var starts = new HashMap<MissionInstant, List<Load>>();
    var ends = new HashMap<MissionInstant, List<Load>>();
    for (var load : loads) {
      if (!horizon.contains(load.window()) || !ids.add(load.activityId()))
        throw new IllegalArgumentException("Duplicate activity or load outside horizon");
      boundaries.add(load.window().start());
      boundaries.add(load.window().end());
      starts.computeIfAbsent(load.window().start(), key -> new ArrayList<>()).add(load);
      ends.computeIfAbsent(load.window().end(), key -> new ArrayList<>()).add(load);
    }
    double battery = initial.batteryWh(),
        stored = initial.storedMb(),
        propellant = initial.propellantKg();
    check(initial.at(), battery, stored, propellant, limits, violations);
    double draw = 0, production = 0, downlink = 0;
    int supplyIndex = 0;
    var points = new ArrayList<>(boundaries);
    for (int i = 0; i < points.size(); i++) {
      var at = points.get(i);
      // Half-open intervals: remove ending activity rates before adding starting rates.
      for (var load : ends.getOrDefault(at, List.of())) {
        draw -= load.drawWatts();
        production -= load.generatedMbPerSecond();
        downlink -= load.downlinkedMbPerSecond();
      }
      for (var load : starts.getOrDefault(at, List.of())) {
        draw += load.drawWatts();
        production += load.generatedMbPerSecond();
        downlink += load.downlinkedMbPerSecond();
        propellant -= load.propellantKg();
      }
      check(at, battery, stored, propellant, limits, violations);
      samples.add(new Sample(at, battery, stored, propellant));
      if (i + 1 == points.size()) break;
      while (ordered.get(supplyIndex).window().end().compareTo(at) <= 0) supplyIndex++;
      var forecast = ordered.get(supplyIndex);
      double dt = seconds(at, points.get(i + 1));
      double batteryRate =
          (forecast.generationWatts() - forecast.busDrawWatts() - Math.max(0, draw)) / 3600.0;
      double storageRate = Math.max(0, production) - Math.max(0, downlink);
      if (!Double.isFinite(batteryRate) || !Double.isFinite(storageRate))
        throw new IllegalArgumentException("Resource rate overflow");
      // Preserve saturation breakpoints so a consumer does not interpolate a fictional straight
      // line.
      var breakpoints = new TreeSet<Long>();
      if (batteryRate > 0 && battery < limits.batteryCapacityWh()) {
        double hit = (limits.batteryCapacityWh() - battery) / batteryRate;
        if (hit > 0 && hit < dt) breakpoints.add(Math.round(hit * 1_000_000_000));
      }
      if (storageRate < 0 && stored > 0) {
        double hit = -stored / storageRate;
        if (hit > 0 && hit < dt) breakpoints.add(Math.round(hit * 1_000_000_000));
      }
      for (long offset : breakpoints)
        if (offset > 0) {
          var instant = at.plus(new MissionDuration(offset));
          if (instant.compareTo(points.get(i + 1)) < 0) {
            double elapsed = offset / 1_000_000_000.0;
            samples.add(
                new Sample(
                    instant,
                    Math.min(limits.batteryCapacityWh(), battery + batteryRate * elapsed),
                    Math.max(0, stored + storageRate * elapsed),
                    propellant));
          }
        }
      battery = Math.min(limits.batteryCapacityWh(), battery + batteryRate * dt);
      // An empty downlink cannot create negative storage credit for a later imaging activity.
      stored = Math.max(0, stored + storageRate * dt);
      if (!Double.isFinite(battery) || !Double.isFinite(stored) || !Double.isFinite(propellant))
        throw new IllegalArgumentException("Resource arithmetic overflow");
    }
    return new Result(
        violations.isEmpty()
            ? ResourceValidation.Status.VALIDATED
            : ResourceValidation.Status.REJECTED,
        initial.evidenceReference(),
        modelReference,
        modeled,
        samples,
        violations);
  }

  private static Result incomplete(
      Initial initial, String model, Set<Resource> modeled, String reason) {
    return new Result(
        ResourceValidation.Status.NOT_EVALUATED,
        initial.evidenceReference(),
        model,
        modeled,
        List.of(),
        List.of(new Violation(initial.at(), Resource.BATTERY, reason)));
  }

  private static void check(
      MissionInstant at,
      double battery,
      double stored,
      double propellant,
      Limits limits,
      List<Violation> violations) {
    if (battery < limits.minimumBatteryWh())
      violations.add(new Violation(at, Resource.BATTERY, "Battery reserve violated"));
    if (battery > limits.batteryCapacityWh())
      violations.add(new Violation(at, Resource.BATTERY, "Initial battery exceeds capacity"));
    if (stored > limits.storageCapacityMb())
      violations.add(new Violation(at, Resource.STORAGE, "Storage capacity exceeded"));
    if (propellant < limits.minimumPropellantKg())
      violations.add(new Violation(at, Resource.PROPELLANT, "Propellant reserve violated"));
  }
}
