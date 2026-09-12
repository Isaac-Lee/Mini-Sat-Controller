package msc.domain.spacecraftcontrol;

import static msc.domain.shared.Checks.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import msc.domain.planning.ScheduleKey;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;

/** Describes a prepared artifact; construction never grants release authority. */
public record CommandLoad(
    CommandLoadId id,
    ScheduleKey scheduleKey,
    long scheduleVersion,
    List<CommandInstance> commands,
    String missionDefinitionVersion,
    TimeCorrelationId timeCorrelationId,
    String checksum,
    Optional<String> authorizationEvidenceReference,
    MissionInstant commitDeadline) {
  public CommandLoad {
    Objects.requireNonNull(id);
    Objects.requireNonNull(scheduleKey);
    positive(scheduleVersion);
    commands = List.copyOf(commands);
    if (commands.isEmpty()) throw new IllegalArgumentException("Empty command load");
    text(missionDefinitionVersion);
    Objects.requireNonNull(timeCorrelationId);
    text(checksum);
    Objects.requireNonNull(authorizationEvidenceReference);
    Objects.requireNonNull(commitDeadline);
    commitDeadline.requireTai();
    if (commands.stream().map(CommandInstance::id).distinct().count() != commands.size())
      throw new IllegalArgumentException("Duplicate command ID");
    if (commands.stream().anyMatch(c -> !c.timeTag().correlationId().equals(timeCorrelationId)))
      throw new IllegalArgumentException("Time correlation mismatch");
  }
}
