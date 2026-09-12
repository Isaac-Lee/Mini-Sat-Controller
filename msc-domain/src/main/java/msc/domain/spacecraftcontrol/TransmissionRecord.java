package msc.domain.spacecraftcontrol;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.time.MissionInstant;

/** Transmission evidence is not spacecraft execution evidence. */
public record TransmissionRecord(
    CommandLoadId loadId,
    MissionInstant transmittedAt,
    String passSessionReference,
    String receiptReference) {
  public TransmissionRecord {
    Objects.requireNonNull(loadId);
    Objects.requireNonNull(transmittedAt);
    text(passSessionReference);
    text(receiptReference);
  }
}
