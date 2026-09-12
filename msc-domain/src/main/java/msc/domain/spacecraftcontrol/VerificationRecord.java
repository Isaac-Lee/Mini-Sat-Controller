package msc.domain.spacecraftcontrol;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.time.MissionInstant;

public record VerificationRecord(
    CommandLoadId loadId,
    ExecutionOutcome outcome,
    MissionInstant observedAt,
    MissionInstant receivedAt,
    String evidenceReference) {
  public VerificationRecord {
    Objects.requireNonNull(loadId);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(receivedAt);
    text(evidenceReference);
  }
}
