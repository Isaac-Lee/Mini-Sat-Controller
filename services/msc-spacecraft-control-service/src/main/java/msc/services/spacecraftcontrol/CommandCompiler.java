package msc.services.spacecraftcontrol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.planning.MissionSchedule;
import msc.domain.shared.Ids.*;
import msc.domain.spacecraftcontrol.*;
import msc.domain.time.*;
import msc.platform.Json;
import msc.platform.StateStore;

/** Compile a pinned committed schedule into semantic commands. Compilation is not release. */
public final class CommandCompiler {
  public record Sources(
      MissionSchedule.Snapshot schedule,
      MissionProfile mission,
      StateStore.State<Correlation> correlation,
      Map<String, CatalogEntry> catalogsByActivity,
      Map<String, Map<String, String>> parametersByActivity,
      MissionInstant deadline) {
    public Sources {
      Objects.requireNonNull(schedule);
      Objects.requireNonNull(mission);
      Objects.requireNonNull(correlation);
      catalogsByActivity = Map.copyOf(catalogsByActivity);
      var copy = new TreeMap<String, Map<String, String>>();
      parametersByActivity.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
      parametersByActivity = Collections.unmodifiableMap(copy);
      Objects.requireNonNull(deadline).requireTai();
    }
  }

  public record Prepared(CommandLoad load, Sources sources, String sourceSha256) {}

  private final Json json;

  public CommandCompiler(Json json) {
    this.json = json;
  }

  public Prepared compile(CommandLoadId id, Sources source) {
    var schedule = MissionSchedule.restore(source.schedule());
    var mission = source.mission();
    var correlation = source.correlation().body();
    if (schedule.version() <= 0
        || schedule.activities().isEmpty()
        || schedule.activities().size() > 100)
      throw new IllegalArgumentException("A committed bounded nonempty schedule is required");
    if (!schedule.key().spacecraftId().value().equals(mission.spacecraftId())
        || !source.correlation().id().equals(mission.spacecraftId())
        || source.correlation().version() <= 0
        || !correlation.spacecraftId().equals(mission.spacecraftId())
        || !correlation.missionDefinitionVersion().equals(mission.missionDefinitionVersion())
        || !correlation.timeCorrelationId().equals(mission.timeCorrelationId()))
      throw new IllegalArgumentException("Mission/schedule/correlation identity mismatch");
    var ids = new HashSet<String>();
    schedule.activities().forEach(a -> ids.add(a.id().value()));
    if (!ids.equals(source.catalogsByActivity().keySet())
        || !ids.equals(source.parametersByActivity().keySet()))
      throw new IllegalArgumentException(
          "Every scheduled activity requires exact catalog and parameter bindings");
    var ordered =
        schedule.activities().stream()
            .sorted(
                Comparator.comparing(
                        (msc.domain.planning.ScheduledActivity a) -> a.window().start())
                    .thenComparing(a -> a.id().value()))
            .toList();
    var commands = new ArrayList<CommandInstance>();
    for (var activity : ordered) {
      var catalog = source.catalogsByActivity().get(activity.id().value());
      if (!catalog.activity().id().equals(activity.definitionId())
          || catalog.activity().version() != activity.definitionVersion()
          || !catalog.activity().exclusiveResources().equals(activity.exclusiveResources()))
        throw new IllegalArgumentException("Scheduled definition/resources do not match catalog");
      var parameters = source.parametersByActivity().get(activity.id().value());
      catalog.template().validate(parameters);
      long start =
          correlation.taiToTick(
              mission.timeCorrelationId(), correlation.clockPartition(), activity.window().start());
      long end =
          correlation.taiToTick(
              mission.timeCorrelationId(), correlation.clockPartition(), activity.window().end());
      long duration =
          BigDecimal.valueOf(catalog.durationSeconds())
              .multiply(BigDecimal.valueOf(correlation.ticksPerSecond()))
              .longValueExact();
      if (duration <= 0 || Math.subtractExact(end, start) != duration)
        throw new IllegalArgumentException("Schedule duration differs from exact catalog duration");
      if (source.deadline().compareTo(activity.window().start()) > 0)
        throw new IllegalArgumentException("Commit deadline follows command start");
      var commandId =
          new CommandId(
              UUID.nameUUIDFromBytes(
                      (id.value() + ":" + activity.id().value()).getBytes(StandardCharsets.UTF_8))
                  .toString());
      commands.add(
          new CommandInstance(
              commandId,
              catalog.template().id() + ":" + catalog.template().version(),
              parameters,
              new OnboardTime(
                  start,
                  correlation.clockPartition(),
                  new TimeCorrelationId(mission.timeCorrelationId()))));
    }
    String hash = json.fingerprint(source);
    String checksum =
        json.fingerprint(Map.of("loadId", id, "sources", source, "commands", commands));
    var load =
        new CommandLoad(
            id,
            schedule.key(),
            schedule.version(),
            commands,
            mission.missionDefinitionVersion(),
            new TimeCorrelationId(mission.timeCorrelationId()),
            checksum,
            Optional.empty(),
            source.deadline());
    return new Prepared(load, source, hash);
  }
}
