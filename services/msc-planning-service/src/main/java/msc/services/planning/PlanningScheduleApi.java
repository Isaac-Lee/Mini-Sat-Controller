package msc.services.planning;

import java.util.Objects;
import msc.domain.planning.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only owner boundary for committed schedule versions; never accepts a caller's snapshot. */
@RestController
public class PlanningScheduleApi {
  public record Query(ScheduleKey key, long version) {
    public Query {
      Objects.requireNonNull(key);
      if (version <= 0) throw new IllegalArgumentException("Committed positive version required");
    }
  }

  private final JdbcScheduleRepository schedules;

  public PlanningScheduleApi(StateStore store, Json json, JdbcTemplate db) {
    schedules = new JdbcScheduleRepository(store, json, db);
  }

  @PostMapping({"/api/planning/schedules/query", "/internal/planning/schedules/query"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public MissionSchedule.Snapshot version(@RequestBody Query request) {
    return schedules
        .version(request.key(), request.version())
        .orElseThrow(() -> ApiException.missing("Committed schedule version not found"))
        .snapshot();
  }

  @PostMapping({"/api/planning/schedules/current", "/internal/planning/schedules/current"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public MissionSchedule.Snapshot current(@RequestBody ScheduleKey key) {
    return schedules.latest(key)
        .orElseThrow(() -> ApiException.missing("Committed schedule not found"))
        .snapshot();
  }
}
