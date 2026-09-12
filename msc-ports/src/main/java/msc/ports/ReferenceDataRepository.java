package msc.ports;

import java.util.Optional;
import msc.domain.referencedata.*;

/** Approved, normalized immutable snapshots only. No implicit latest query. */
public interface ReferenceDataRepository {
  Optional<ReferenceDataSnapshot> find(SnapshotRef exactReference);
}
