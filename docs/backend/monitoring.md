# Monitoring: observations and versioned operational estimates

Monitoring is the seventh independent service application (local port 8109),
with its own PostgreSQL database and `msc.monitoring.v1` RabbitMQ consumer queue.
It implements durable operational telemetry intake, evidence accounting and a
read model of mode/battery/storage/propellant. It is not an authority over actual
spacecraft state, an anomaly service, a command release gate or a physical simulator.

## Evidence and ordering

An ADMIN registers an immutable, sequentially versioned admission `Binding`:
spacecraft ID, source name, SIMULATION/HARDWARE environment, maximum evidence age,
allowed future clock skew and an approval reference. Simulation sources must start
with `simulator:` and cannot be labelled HARDWARE. No hardware receiver is enabled
by registering a binding. A binding is an intake/freshness policy, not mission
qualification or authorization to command the spacecraft.

A `Frame` carries a UUID, source/binding version, source sequence, observed TAI
instant, quality, mode, battery Wh, storage MB, propellant kg and provenance.
Monitoring assigns receivedAt from its injected Clock. Negative but finite
resource values and unknown modes are retained as evidence and excluded from the
accepted estimate. Frames must match the configured source. Sequence reuse with
a different frame ID and contradictory content under an existing frame ID conflict.
A source restarting its sequence must receive a new approved binding version.

Ordering is by observation instant, with source sequence as the tie-breaker.
Later arrival does not mean newer observation. The projection separately retains:

- the newest usable GOOD frame;
- the newest admitted evidence, including bad quality.

An intermediate delayed GOOD frame can improve the accepted estimate while newer
bad-quality evidence continues to mark it DEGRADED. Future observations beyond the
binding's skew allowance are accounted without advancing either ordering frontier.
Old frames remain queryable and cannot rewind the estimate. A binding change clears
the projection to UNKNOWN; delayed frames from a known superseded binding are
accounted as SUPERSEDED_BINDING without altering the new projection.

Confidence is computed when the current-state API is read:

| Confidence | Meaning |
| --- | --- |
| UNKNOWN | No usable evidence for the active binding |
| STALE | Accepted observation age is at least the configured maximum |
| DEGRADED | Newer unusable evidence exists, or accepted time is still ahead of evaluation time |
| FRESH | Usable evidence within the configured age, without newer unusable evidence |

Freshness uses observedAt, not receivedAt. FRESH still means an estimate, and may
contain mode SAFE; it does not imply safe command release. Anomaly and release
services must check the evidence, mode, resource policy and current time at their
own decision boundary. Staleness is not currently emitted by a timer event.

## Consistency and API

Per-spacecraft PostgreSQL advisory locks serialize ingestion across replicas.
Immutable receipts, source sequence uniqueness, updated estimate history and
outbox facts share a transaction. Broker inbox deduplication shares that transaction.
The raw accepted observation is not overwritten when a newer estimate is created.
Historical estimate responses preserve the binding used for that version.

| Method | Path | Authorization/result |
| --- | --- | --- |
| POST | `/api/telemetry-bindings` | ADMIN, Idempotency-Key; immutable binding and reset estimate |
| POST | `/internal/telemetry` | SERVICE, Idempotency-Key; frame receipt |
| GET | `/api/spacecraft-estimates/{spacecraftId}` | Authenticated; versioned estimate, evaluatedAt, current confidence |
| GET | `/internal/spacecraft-estimates/{spacecraftId}` | SERVICE equivalent |
| GET | `/internal/spacecraft-estimates/{spacecraftId}/versions/{version}` | Pinned immutable estimate, including its binding and evidence |
| GET | `/internal/telemetry/{spacecraftId}/{frameId}` | Immutable frame, server receivedAt and disposition |

Authenticated `/api` equivalents exist for historical estimates and receipts.
Binding registration uses version 1 initially, then exactly the next version.
An exact repeated binding is idempotent without clearing the estimate again.
Changed versions cannot reuse old evidence implicitly.

`TelemetryReceived` events use the same Frame contract. Monitoring emits
`SpacecraftStateProjected` when its persisted estimate changes and
`TelemetryFrameAccounted` for a newly recorded frame. Missing or contradictory
source contracts fail and use the shared retry/DLQ policy. Configure bindings
before emission. Execution events do not fabricate numerical sensor readings.

## Simulator scenarios

The Simulator adds ADMIN-only `POST /api/simulation/telemetry` with Idempotency-Key.
It persists the frame and publishes `TelemetryReceived` through its transactional
outbox. This is explicitly **scenario evidence injection**. It does not yet evolve
vehicle resources, execute activities or simulate a sensor/radio stream automatically.
Those portions remain part of the full backend work plan.

Example binding for a test craft (not actual SPACEEYE-T1 hardware):

```json
{
  "spacecraftId": "sim-spaceeye-t1",
  "version": 1,
  "source": "simulator:spaceeye-scenario",
  "environment": "SIMULATION",
  "maximumAgeSeconds": 60,
  "futureSkewSeconds": 5,
  "approvalReference": "operator-approved-simulation-scenario"
}
```

The public orbit key `norad-63229` and physical mission/simulator configuration
remain separately identified. This service supplies no invented battery or sensor
specifications for the real satellite.

## Verification

At the Monitoring checkpoint, the full reactor passed **78 default tests**, including five new domain cases and
four PostgreSQL integration cases for Monitoring. They cover observation-time
freshness, quality degradation/recovery, future timestamps, source environment,
concurrent ingestion, identity conflicts, immutable history, binding rotation and
inbox/estimate/outbox rollback. The prior seven explicit Orekit integration tests
remain separately documented in the public-orbit checkpoint.

```sh
./scripts/run-local-service.sh monitoring 8109
# Run simulator 8114 and Flight Dynamics 8103 as described in local-runtime.md.
python3 scripts/verify-monitoring.py

# Optional temporary second Monitoring JVM:
./scripts/run-local-service.sh monitoring 18109
python3 scripts/verify-monitoring.py --replica-port 18109
```

The live verifier creates explicit synthetic craft/source IDs. It tests Simulator
outbox → RabbitMQ → Monitoring inbox, API roles, duplicates, reversed arrival,
quality changes, future evidence, immutable history, source rotation and staleness.
Current and historical estimates were also read successfully after restarting
Monitoring. Two-JVM verification additionally checks concurrent idempotency and a
shared current estimate. This is local process scale-out evidence, not Kubernetes
or throughput qualification. Stop the temporary replica after verification.

Ephemeral evidence is in `.local/monitoring-verification.json`. Automatic planning,
synchronous release interlocks and real-time vehicle simulation remain incomplete;
[safety latches and Anomaly records](safety-and-anomaly.md) are now implemented; this checkpoint does not complete the backend goal.
