# ADR-0007 — TAI-oriented mission time and Clock port

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Generic platform instants do not express mission time scales, onboard clocks or replay semantics.

## Decision

Use scale-tagged MissionInstant; TAI-only physical windows/arithmetic; MissionDuration; OnboardTime with partition and correlation identity. Obtain now through the Clock port.

## Consequences

VirtualClock is deterministic. Mixed scales fail rather than silently convert. UTC display, UT1 rotation and real-clock conversion need validated versioned reference data; no leap/EOP algorithm is invented.

## Reference data note — 2026-11-07

Leap-second and EOP data now come from validated, versioned `orekit-data` snapshots collected by
reference-data, with explicit FRESH/STALE/EXPIRED status. See
[reference archive](../../backend/reference-archive.md). Flight-dynamics changes snapshot only
through an explicit digest-pinned rollout. The platform `VersionedUtcTaiClock` offset is still
configured separately.
