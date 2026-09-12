package msc.contracts;

import msc.domain.flightdynamics.MeanElements;
import msc.domain.time.MissionInstant;

public final class OrbitReferenceContracts {
  private OrbitReferenceContracts() {}

  public record Snapshot(
      String id,
      MeanElements elements,
      String provider,
      String sourceUrl,
      MissionInstant fetchedAt,
      String rawSha256,
      String rawJson) {}

  public record TrackedSatellite(int noradId, String displayName, boolean enabled) {
    public TrackedSatellite {
      if (noradId < 1 || noradId > 999999999)
        throw new IllegalArgumentException("Invalid NORAD ID");
      msc.domain.shared.Checks.text(displayName);
      if (displayName.length() > 120) throw new IllegalArgumentException("Name too long");
    }
  }
}
