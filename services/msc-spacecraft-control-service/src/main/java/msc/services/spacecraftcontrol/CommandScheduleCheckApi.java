package msc.services.spacecraftcontrol;

import java.util.*;
import msc.domain.planning.MissionSchedule;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Binding;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Live owner read for diagnostics and later release orchestration; never a release permit. */
@RestController
public class CommandScheduleCheckApi {
  public enum Reason { SCHEDULE_MISSING, STALE_SCHEDULE, SCHEDULE_CONTENT_MISMATCH, DEADLINE_PASSED }
  public record Check(Binding binding, MissionInstant evaluatedAt,
      Optional<MissionSchedule.Snapshot> currentSchedule, Set<Reason> reasons) {
    public Check {
      reasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, reasons);
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Clock clock;

  public CommandScheduleCheckApi(StateStore store, ServiceHttp http, Clock clock) {
    this.store = store;
    this.http = http;
    this.clock = clock;
  }

  @PostMapping({"/api/command-loads/{id}/schedule-check", "/internal/command-loads/{id}/schedule-check"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public Check check(@PathVariable String id) {
    var prepared = store.require("prepared-command-load", id, CommandCompiler.Prepared.class).body();
    var load = prepared.load();
    var reasons = EnumSet.noneOf(Reason.class);
    Optional<MissionSchedule.Snapshot> current;
    try {
      var snapshot = http.post("planning", "/internal/planning/schedules/current", load.scheduleKey(),
          UUID.randomUUID().toString(), MissionSchedule.Snapshot.class);
      MissionSchedule.restore(snapshot);
      if (!snapshot.key().equals(load.scheduleKey()))
        throw ApiException.invalid("Planning returned a different schedule key");
      current = Optional.of(snapshot);
      if (snapshot.version() != load.scheduleVersion()) reasons.add(Reason.STALE_SCHEDULE);
      else if (!snapshot.equals(prepared.sources().schedule())) reasons.add(Reason.SCHEDULE_CONTENT_MISMATCH);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
      current = Optional.empty();
      reasons.add(Reason.SCHEDULE_MISSING);
    }
    // Capture after the owner round trip so an elapsed deadline cannot pass on an old timestamp.
    var now = clock.now();
    if (now.compareTo(load.commitDeadline()) >= 0) reasons.add(Reason.DEADLINE_PASSED);
    return new Check(Binding.of(load), now, current, reasons);
  }
}
