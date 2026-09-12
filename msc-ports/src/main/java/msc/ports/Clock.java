package msc.ports;

import msc.domain.time.MissionInstant;

/** Returns physical TAI time. Real adapters require versioned UTC/TAI conversion. */
@FunctionalInterface
public interface Clock {
  MissionInstant now();
}
