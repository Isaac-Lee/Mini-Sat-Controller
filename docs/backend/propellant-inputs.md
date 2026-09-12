# Source-bound simulation propellant estimates

Mission Definition stores ADMIN-approved, versioned simulation mass-observation
models. Each binds a spacecraft, mission-definition version and telemetry binding
version. The model explicitly specifies absolute mass uncertainty in kg, a maximum
unmodeled loss rate in kg/s and a maximum propagation duration (up to two days).
It is not a pressure/temperature tank estimator or a hardware-qualified model.

Flight Dynamics reads the requested current model and current Monitoring estimate
over authenticated HTTP. It rejects changed version expectations, stale/degraded
observations, future observation epochs, mismatched bindings and non-simulation
sources. External owner reads happen outside its database transaction. It preserves
the full source estimate/model and their versions, source fingerprint, nominal
mass, epoch, model identity and propagation limit in an immutable artifact.

The modeled lower bound at elapsed time `dt` seconds is:

```
max(0, observedMassKg - absoluteUncertaintyKg - dt * maximumUnmodeledLossKgPerSecond)
```

Arithmetic preserves nanosecond elapsed time with decimal quantities. A time before
the epoch or after the configured propagation limit is rejected. Planned activity
consumption must additionally be accounted for by the resource timeline; this
formula does not subtract future commanded burns or prove their feasibility.
The bound is conditional on the approved synthetic model's assumptions, not a
physical confidence interval or proof of available spacecraft fuel.

## APIs

Mission Definition:

- `POST /api/propellant-models`: ADMIN and `Idempotency-Key`; body
  `{expectedVersion,model}`. Initial version expects zero. Model fields are
  `spacecraftId`, `missionDefinitionVersion`, `environment:SIMULATION`,
  `telemetryBindingVersion`, `absoluteUncertaintyKg`,
  `maximumUnmodeledLossKgPerSecond`, `maximumPropagationSeconds`, `approvalReference`.
- `GET /api/propellant-models/{id}` and `/versions/{version}` read current/exact
  versions. Equivalent internal GET paths require SERVICE.

Flight Dynamics:

- `POST /api/propellant-estimates` or `/internal/propellant-estimates`: body
  `{spacecraftId,telemetryVersion,modelVersion}`, `Idempotency-Key` required.
- `GET /api/propellant-estimates/{id}` or `/internal/propellant-estimates/{id}`:
  exact immutable artifact.

Reads/estimation permit ADMIN/OPERATOR/SERVICE; internal routes additionally require
SERVICE. A repeated estimation key replays its historical artifact. Replay is not
a new freshness decision. A new key rechecks current source versions/freshness;
identical pinned source/model content reuses the existing artifact under a DB lock.
Model changes publish `PropellantModelPublished` to wake Planning. Artifact reads
and captures do not emit a recursive input-change event.

## Planning integration

Planning requires the returned model and Monitoring source to equal the versions
it captured, checks model mission binding, source freshness and propagation coverage
of the whole planning horizon, then pins the artifact as PROPELLANT. A failure is
an explicit missing input. The resource evaluator still needs to consume this
bound with the power/storage/propellant timeline before scheduling can commit.

## Verification

On 2026-09-12 the full default suite passed 102 tests. After the final Planning
start-time/freshness checks, its seven tests and packaging passed again. The live
Planning verifier passed fourteen checks, including both new propellant scenarios.

PostgreSQL-backed tests exercise decimal/nanosecond bounds, time-range rejection,
source preservation, replay after observation expiry, changed-version rejection and
telemetry binding mismatch. The live Planning verifier publishes a model and
Simulator telemetry, verifies Flight Dynamics and Planning pin the same artifact,
rotates the model to an unmatched telemetry binding, checks rejection and reads the
old model/estimate unchanged. Test artifacts are explicitly synthetic.
