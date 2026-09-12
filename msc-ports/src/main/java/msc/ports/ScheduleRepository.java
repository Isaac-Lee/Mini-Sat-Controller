package msc.ports;

import java.util.List;
import java.util.Optional;
import msc.domain.planning.*;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.TimeWindow;

/**
 * Publication must atomically compare the expected version, append history and advance the head.
 */
public interface ScheduleRepository {
  Optional<MissionSchedule> latest(ScheduleKey key);

  Optional<MissionSchedule> version(ScheduleKey key, long version);

  /**
   * All current heads intersecting this physical horizon, including differently keyed schedules.
   */
  List<MissionSchedule> overlapping(SpacecraftId spacecraftId, TimeWindow horizon);

  void commit(long expectedVersion, MissionSchedule next);

  final class VersionConflict extends RuntimeException {
    public VersionConflict() {
      super("Schedule head changed; replan from current version");
    }
  }
}
