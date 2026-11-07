# Attitude maneuver estimates (kinematic, simulation only)

Flight Dynamics estimates rest-to-rest slew time between consecutive
[required target-pointing](required-target-pointing.md) samples under a pinned approved
agility model. Scope is `KINEMATIC_MODEL_NO_DYNAMICS` and environment is `SIMULATION`.
See [ADR-0015](../architecture/adr/0015-kinematic-attitude-maneuver.md).

This is **not** attitude determination, attitude control, a commanded attitude, or a claim
that the spacecraft can track or settle on a target.

## Request

`POST /api/maneuver-estimates` with an `Idempotency-Key`; ADMIN, OPERATOR and SERVICE may
call it. `/internal/maneuver-estimates` requires SERVICE credentials.

```json
{
  "spacecraftId": "sim-sat-1",
  "agilityModelVersion": 3,
  "maximumAccelerationDegreesPerSecondSquared": 0.5,
  "targets": [
    {"requiredTargetPointingId": "<id>", "instant": {"seconds": 1000, "nanos": 0, "scale": "TAI"}},
    {"requiredTargetPointingId": "<id>", "instant": {"seconds": 1010, "nanos": 0, "scale": "TAI"}}
  ]
}
```

- 2..256 targets with strictly increasing TAI instants. Each instant must exactly match a
  sample of the referenced stored result; there is no interpolation.
- Every referenced result must belong to `spacecraftId`, and all must share one orbit
  `solutionId`, `orbitSourceHash` and `referenceDigest`; mixed sources are rejected.
- The agility model is read from mission-definition at exactly `agilityModelVersion`.
- The acceleration limit, in (0, 180] deg/s², is a caller-supplied simulation assumption
  because the approved agility model has none. It is recorded on the result with
  `maximumAccelerationSource: CALLER_SIMULATION_ASSUMPTION`, so a `feasible` flag always rests
  on that caller choice.

`GET /api/maneuver-estimates/{id}` returns the stored plan.

## Model

| Quantity | Unit | Source |
|---|---|---|
| slew angle θ | deg | angle between EME2000 line-of-sight unit vectors |
| max rate ω | deg/s | `AgilityContracts.Model.slewRateDegreesPerSecond` |
| max acceleration α | deg/s² | request |
| settling | s | `AgilityContracts.Model.settlingSeconds` |
| available time | s | difference of the two TAI sample instants |

- θ = 0: shape `NONE`, required time 0 (no settling).
- θ ≤ ω²/α: `TRIANGULAR`, slew = 2√(θ/α), peak rate √(θα).
- θ > ω²/α: `TRAPEZOIDAL`, slew = θ/ω + ω/α, peak rate ω.
- required = slew + settling. At θ = ω²/α both formulas give 2ω/α.

A transition is infeasible for any of:

- `INSUFFICIENT_TIME`: required > available.
- `OFF_NADIR_LIMIT_EXCEEDED`: either endpoint's required off-nadir > approved limit.
- `TARGET_NOT_VISIBLE`: either endpoint sample records the target below its elevation mask.

The plan is feasible only if every transition is.

## Limits

Only boresight direction is modeled; rotation about the boresight is ignored. Endpoints are
treated as inertially at rest, so the line-of-sight motion needed to track a ground target
during and after the slew is ignored. No inertia, torque, momentum, flexible-mode, sensor or
controller model exists. The estimate does not promote Planning's `ATTITUDE_SEQUENCE` gate,
and the `ManeuverEstimated` event has no subscriber.

## Verification

`RestToRestSlewTest` (domain) covers zero angle, the triangular/trapezoidal boundary, rate
and acceleration limits and input rejection. `ManeuverEstimateApiTest` (PostgreSQL
Testcontainers, mocked mission-definition HTTP) covers per-transition feasibility flags,
persistence, replay, determinism across keys and binding rejection. These controller tests
do not exercise HTTP security.
