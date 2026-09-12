package msc.domain.planning;

import java.util.EnumSet;
import java.util.Map;
import msc.domain.referencedata.SnapshotRef;

/** Pins every input category; no implicit latest-data fallback. */
public record PlanningDataSnapshot(Map<Input, SnapshotRef> references) {
  public enum Input {
    ORBIT,
    AGILITY,
    PROPELLANT,
    SPACECRAFT_STATE,
    WEATHER,
    LEAP_SECONDS,
    EOP,
    GROUND_SCHEDULE,
    MISSION_DEFINITION,
    POLICY
  }

  public PlanningDataSnapshot {
    references = Map.copyOf(references);
    if (!references.keySet().equals(EnumSet.allOf(Input.class)))
      throw new IllegalArgumentException("All planning inputs must be pinned");
  }
}
