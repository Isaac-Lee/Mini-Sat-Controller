package msc.contracts;

import java.util.List;
import msc.domain.referencedata.ReferenceDataSnapshot;
import msc.domain.time.MissionInstant;

/** Orekit UTC/EOP archive snapshots. All instants are TAI. */
public final class ReferenceArchiveContracts {
  private ReferenceArchiveContracts() {}

  public enum Freshness {
    FRESH,
    STALE,
    EXPIRED
  }

  public record SourceFile(String path, String url, String gitBlob, String sha256, long bytes) {}

  /**
   * Immutable archive identity. {@code latestMeasured} is the last IERS-measured (flag I) UT1-UTC
   * day; {@code snapshot.validity} is [EOP start, expiresAt).
   */
  public record ArchiveSnapshot(
      ReferenceDataSnapshot snapshot,
      String sourceCommit,
      String archivePath,
      String archiveSha256,
      List<SourceFile> files,
      MissionInstant eopStart,
      MissionInstant eopEnd,
      MissionInstant latestMeasured,
      MissionInstant expiresAt) {}

  public record ArchiveStatus(
      ArchiveSnapshot current,
      Freshness freshness,
      String warning,
      MissionInstant evaluatedAt,
      boolean collectionEnabled,
      String lastError,
      String lastCheckedAt) {}
}
