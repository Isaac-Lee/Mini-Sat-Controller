# Anomaly and durable safety freezes

Anomaly is the eighth independent service (local port 8110), owning its PostgreSQL
database, immutable incident/history records and RabbitMQ inbox/outbox. It consumes
Monitoring's `SpacecraftStateProjected` events and independently queries current
Monitoring evidence for safety checks and recovery decisions.

## Policy and incident model

ADMIN configures a versioned `SafetyPolicy` per spacecraft, pinning the Monitoring
binding version and explicit battery Wh, storage MB and propellant kg thresholds.
The policy also requires one or two distinct recovery operators and sets approval
validity (1..600 seconds). There are no hardcoded SPACEEYE-T1 resource specifications.
The numerical values in the verifier are labelled synthetic scenario configuration.

A new or changed policy starts frozen until explicit operator enablement with
healthy, fresh evidence. SAFE mode, threshold violations, unknown/degraded/stale
estimates, source-binding mismatch and unavailable Monitoring block clearance.
A normal observation never automatically clears a latched incident.

New critical facts create immutable `Anomaly` records (CRITICAL severity) with
spacecraft identity, declaration time, Monitoring evidence reference, policy version,
incident generation and reasons. They emit `AnomalyDeclared`. The latch separately
emits `PlanningFrozen` when it changes. Multiple facts can belong to the same
incident generation. A successful operator recovery records a durable resolution
for that policy/generation and emits `PlanningFreezeCleared`. Historical facts and
approvals remain unchanged. Changing a policy does not rewrite earlier incident
records or manufacture resolutions for old policy generations.

Prior local development fixtures created before explicit Anomaly records were
introduced retain their safety history; no retrospective records were fabricated.

## Ordering and approvals

The highest Monitoring projection version is cached. A synchronous check rejects
an older read if a newer projection has already been observed, instead of approving
against known superseded evidence. Late first-seen critical physical evidence can
still latch an incident even when a newer nominal projection arrived first.
Historical staleness alone does not relatch a current healthy projection. Evidence
already reviewed and cleared is not treated as a new incident on replay. Fact
fingerprints sort reason names so identities remain stable across JVMs.

Recovery reads current Monitoring over authenticated HTTP before taking the
per-spacecraft database lock, then rechecks projection version, current time,
policy and latch under that lock. Missing, stale or unsafe evidence returns a
structured denied result and preserves the freeze. The caller must supply the
expected safety version and a decision reference. Authenticated actor identity,
not a submitted actor name, determines who approved. The same operator counts
once. Expired approvals do not count; new critical evidence invalidates pending
approvals. Policy changes discard approvals for the former configuration.

Source frame values remain estimates. A clear safety assessment is **not** command
authority, an atomic cross-service release permit, permission to bypass a freeze,
or proof that a spacecraft is physically safe. The future Control/Space Link release
path must compose this evidence with schedule/resource/booking/authority checks,
define its final dispatch consistency boundary and revalidate before sending.
No command transmission or contingency maneuver is implemented by this service.

## Telemetry silence and replicas

A durable watchdog claims one due spacecraft per poll using PostgreSQL row locks,
SKIP LOCKED and a 30-second lease. Each completed claim schedules the next nominal
check after five seconds. A process polls once per second; total cadence depends
on fleet size, remote latency and replica count and is not a guaranteed five-second
fleet-wide SLA. Competing replicas share claims. No database transaction is held
while querying Monitoring. Silent telemetry and transport failures therefore remain
observable without a new telemetry event. Direct checks also evaluate current
freshness on every call, independent of watchdog timing.

## APIs

Anomaly APIs require OPERATOR, ADMIN or SERVICE, with stricter mutation roles below.
Internal paths additionally require SERVICE. No unauthenticated or requester access
to operational incident/approval records is provided.

| Method | Path | Meaning |
| --- | --- | --- |
| POST | `/api/safety-policies` | ADMIN, Idempotency-Key; sequential policy version and frozen initial latch |
| GET | `/api/safety/{spacecraftId}` | Persisted latch; does not itself assess current freshness |
| GET | `/api/safety/{spacecraftId}/history` | Immutable latch versions and scoped operator approvals |
| POST | `/internal/safety/{spacecraftId}/check` | Fresh synchronous assessment with current reasons, policy/estimate/latch versions and evaluation time |
| POST | `/api/safety/{spacecraftId}/check` | OPERATOR/ADMIN equivalent |
| POST | `/api/safety/{spacecraftId}/recovery-approvals` | OPERATOR/ADMIN, Idempotency-Key; expectedSafetyVersion and decisionReference |
| GET | `/api/anomalies?limit=100` | Immutable incident records, bounded to at most 500 |
| GET | `/api/anomalies/{id}` | Incident plus optional policy/generation resolution record |

The check endpoint deliberately does not cache an idempotent clear response.
Recovery POST responses are historical idempotent results; after a later incident,
a replayed successful response cannot be used as present permission. Read the latch
and perform a new safety check. HTTP 409 means a concurrency/version conflict;
a successful HTTP response containing `approved:false` or `clear:false` is a denial.
Other failed responses also provide no clearance.

Example policy is defined in `scripts/verify-safety.py`. Configure Monitoring and
supply explicit simulator evidence before enabling a scenario. Local `operator1`
and `operator2` represent distinct test actors; production identity uses the existing
OIDC boundary and has not been tested with an external IdP in this checkpoint.

## Verification and remaining work

The full test suite passes **87 default tests**. New tests cover distinct/expired
operator approvals, generation invalidation, threshold/freshness conditions, delayed
critical evidence, replay of reviewed facts, older projection rejection, monitoring
outage, incident resolution history, inbox/state/outbox rollback and watchdog
silence detection. Two simultaneous watchdogs were tested against PostgreSQL to
prove only one claimed the same spacecraft while the remote read was in progress.

```sh
./scripts/run-local-service.sh anomaly 8110
# Requires Simulator 8114, Monitoring 8109 and Flight Dynamics 8103.
python3 scripts/verify-safety.py
```

The actual API/PG/RabbitMQ run passed role checks, operator enablement, two-person
recovery, SAFE telemetry freeze, denied unsafe recovery, no automatic unfreeze on
nominal telemetry, approval invalidation after a new fault, policy/source revision,
timer-only detection of stopped telemetry, immutable history and resolved/unresolved
Anomaly records. The persisted latch/version/generation also survived process
restart. Detailed ephemeral evidence is `.local/safety-verification.json`.

The full backend remains incomplete: automatic planning, final command-release
consistency, spacecraft execution simulation, payload/product delivery and Kubernetes
end-to-end/scale verification are still required. This service does not implement
thermal/wheel-momentum anomaly policies or automated contingency recovery.
