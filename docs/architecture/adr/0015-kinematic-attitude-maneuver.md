# ADR-0015 — Kinematic rest-to-rest attitude maneuver estimates

Status: Proposed (PR for data.js `attitude-maneuver`); requires human acceptance.

## Context

Required-target-pointing results give a sampled line of sight, and the approved
mission-definition agility model gives an off-nadir limit, a maximum slew rate and a
settling time. Planning needs a reproducible estimate of whether consecutive pointing
targets can be reached in time. MSC has no spacecraft inertia, actuator, attitude
determination or attitude control model, and none is in scope.

## Decision

Flight Dynamics owns a new public contract, `msc.contracts.ManeuverContracts`, served at
`POST /api/maneuver-estimates` (ADMIN, OPERATOR, SERVICE) and stored as an immutable
`maneuver-estimate` with a `ManeuverEstimated` outbox event.

- The slew angle is the angle between two EME2000 line-of-sight unit vectors taken from
  stored required-target-pointing samples at exact sample instants (no interpolation).
- Time is a symmetric rest-to-rest bang-coast-bang profile: triangular when
  `angle <= rate²/acceleration`, otherwise trapezoidal, plus the approved settling time.
  A zero angle needs neither motion nor settling.
- The rate and settling time come from a pinned approved agility model version. The
  approved model has no acceleration limit, so the caller supplies one explicitly and the
  result records it as a simulation assumption.
- A transition is infeasible for `INSUFFICIENT_TIME`, `OFF_NADIR_LIMIT_EXCEEDED` or
  `TARGET_NOT_VISIBLE`; the plan is feasible only if every transition is.
- Every result carries environment `SIMULATION` and scope `KINEMATIC_MODEL_NO_DYNAMICS`.

## Consequences

The estimate ignores rotation about the boresight, target motion during and after the
slew (endpoints are treated as inertially at rest), torque/momentum limits, flexible modes,
sensor and controller behaviour. It is not attitude determination, control, a commanded
attitude, or evidence that the spacecraft can track or settle on a target. It does not
promote Planning's `ATTITUDE_SEQUENCE` gate. Replacing it with a dynamics model requires a
new scope value and a superseding ADR.
