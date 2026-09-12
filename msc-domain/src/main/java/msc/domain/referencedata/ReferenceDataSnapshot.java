package msc.domain.referencedata;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.time.TimeWindow;

public record ReferenceDataSnapshot(
    SnapshotRef reference, String kind, TimeWindow validity, String manifestReference) {
  public ReferenceDataSnapshot {
    Objects.requireNonNull(reference);
    text(kind);
    Objects.requireNonNull(validity);
    text(manifestReference);
  }
}
