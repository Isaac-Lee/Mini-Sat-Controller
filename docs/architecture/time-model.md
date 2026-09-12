# Mission time

Physical calculations and scheduling use TAI. User presentation uses UTC primarily
and detected local time secondarily. Earth rotation may require UT1. Spacecraft
use onboard ticks correlated through a versioned TimeCorrelation reference.

`MissionInstant(seconds, nanos, scale)` carries explicit TimeScale (TAI, UTC, UT1,
TT, GPS). The counter origin is 1970-01-01 00:00:00 in the named scale. This is a
scale-tagged scalar, not a civil UTC parser or a leap-second encoding algorithm.
UTC/UT1/TT/GPS tags anticipate conversion boundaries; they do not imply conversion
support. A Java `Instant` or Unix timestamp must never simply be relabeled TAI.

TimeWindow requires TAI and defines `[start, end)` with start strictly before end.
Adjacent exclusive activities are allowed. Comparison rejects mixed scales;
duration arithmetic requires TAI. MissionDuration uses nonnegative integer
nanoseconds; MissionInstant validates fractional nanos and checks arithmetic
overflow. Signed offsets, long-span high-precision durations and mission-specific
precision/range rules are open modeling work, not silently approximated doubles.

OnboardTime holds ticks, clock partition/reset identity and TimeCorrelationId.
The eventual immutable correlation artifact needs validity, model coefficients,
uncertainty, source observations and version. It must distinguish clock resets,
wraparound and extrapolation from valid interpolation. Conversion algorithms are
outside v0.1.

Clock in `msc-ports` supplies TAI now to application behavior. VirtualClock in
infrastructure advances only when instructed and is used by deterministic tests.
No RealClock is supplied: safely bridging system UTC requires a validated,
versioned leap-second/time-conversion adapter. ReplayClock/AcceleratedClock will
implement the same port. Domain models receive time values as method inputs and
never read a system clock.

Future conversion ports/adapters consume exact leap-second, EOP and correlation
snapshots. Planning pins these inputs for reproducibility. Do not implement IERS,
leap-second or Earth-rotation algorithms from scratch. Late telemetry preserves
observedAt and receivedAt separately; neither is substituted for the other.
