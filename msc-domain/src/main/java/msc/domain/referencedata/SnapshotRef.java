package msc.domain.referencedata;

import static msc.domain.shared.Checks.*;

import java.util.Objects;
import msc.domain.shared.Ids.SnapshotId;
import msc.domain.time.MissionInstant;

public record SnapshotRef(
    SnapshotId id, long version, String provenance, MissionInstant capturedAt) {
  public SnapshotRef {
    Objects.requireNonNull(id);
    positive(version);
    text(provenance);
    Objects.requireNonNull(capturedAt);
  }
}
