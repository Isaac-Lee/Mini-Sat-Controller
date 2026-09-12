package msc.infrastructure.persistence;

import java.util.*;
import msc.domain.planning.*;
import msc.ports.ScheduleRepository;

/** Single-process atomic reference adapter. Not durable or a distributed writer lease. */
public final class InMemoryScheduleRepository implements ScheduleRepository {
  private final Map<ScheduleKey, NavigableMap<Long, MissionSchedule>> history = new HashMap<>();

  public synchronized Optional<MissionSchedule> latest(ScheduleKey key) {
    var versions = history.get(key);
    return versions == null ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
  }

  public synchronized Optional<MissionSchedule> version(ScheduleKey key, long version) {
    var versions = history.get(key);
    return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
  }

  public synchronized void commit(long expectedVersion, MissionSchedule next) {
    Objects.requireNonNull(next);
    long currentVersion = latest(next.key()).map(MissionSchedule::version).orElse(0L);
    if (expectedVersion != currentVersion || next.version() != Math.addExact(expectedVersion, 1))
      throw new VersionConflict();
    var previous = latest(next.key()).orElseGet(() -> MissionSchedule.empty(next.key()));
    next.requireSuccessorOf(previous);
    for (var other : overlapping(next.key().spacecraftId(), next.key().horizon())) {
      if (!other.key().equals(next.key())) next.requireCrossHorizonSuccessorOf(previous, other);
    }
    history.computeIfAbsent(next.key(), ignored -> new TreeMap<>()).put(next.version(), next);
  }

  public synchronized List<MissionSchedule> overlapping(
      msc.domain.shared.Ids.SpacecraftId spacecraftId, msc.domain.time.TimeWindow horizon) {
    return history.entrySet().stream()
        .filter(
            entry ->
                entry.getKey().spacecraftId().equals(spacecraftId)
                    && entry.getKey().horizon().overlaps(horizon))
        .map(entry -> entry.getValue().lastEntry().getValue())
        .sorted(Comparator.comparing(schedule -> schedule.key().horizon().start()))
        .toList();
  }
}
