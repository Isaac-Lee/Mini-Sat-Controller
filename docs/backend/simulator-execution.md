# Simulator onboard execution

Status: completion-effect arithmetic and persistent scenario initialization are implemented.
Durable receipt/execution records, command-clock advancement and delivery/reconciliation remain in progress. This is
an incremental part of the full spacecraft and ground-station simulator requirements.

`SimulatorOperationEffects` consumes a resolved catalog, matching operation-resource profile,
pinned mission capacities and immutable initial reservoirs. It validates the exact catalog
identity/version, operation and resource figures before calculating any effect. The caller
must resolve and preserve the actual source versions and spacecraft/mission bindings; the
calculator alone does not prove those cross-service bindings or release authority.

At modeled completion, IMAGE adds the declared generated megabytes. DOWNLINK first adds any
declared housekeeping data, then removes at most the available storage using the explicitly
published rate multiplied by catalog duration. Both operations consume the catalog's declared
propellant. A production-before-drain capacity checkpoint is enforced, so a DOWNLINK cannot
hide an intermediate overflow merely because its final storage would be lower. This is a
defined checkpoint in an atomic-completion model, not a continuous physical peak estimate.

Invalid initial reservoirs or mismatched evidence are rejected. Unsupported MANEUVER,
nonfinite arithmetic, storage overflow and insufficient propellant return an unchanged state
and no partially applied production, drain or burn. A successful result is `APPLIED`, not a
ground-observed command `EXECUTED` assertion. The future executor must persist the effect
and command ledger together, with duplicate suppression, ordered completion and restart proof.

The result retains declared power consumption but does not yet evolve a battery. Battery,
orbital and attitude evolution remain required follow-up implementation, using explicit
simulation models rather than inferred SPACEEYE-T1 hardware specifications. A modeled drain
is neither ground reception evidence nor authorization to delete durable payload content.

Six focused Maven tests passed (`SimulatorOperationEffectsTest`, log
`/private/tmp/msc-simulator-effects.log`): IMAGE-to-DOWNLINK sequence and absence of future drain
credit, peak overflow, propellant rejection for both operations, unsupported maneuver,
invalid initial state/evidence mismatch, and finite-input arithmetic overflow. This is pure
calculation verification; no new API or deployed command execution is claimed.

## Persistent scenario initialization

`POST /api/simulation/scenarios` is ADMIN-only and requires an `Idempotency-Key`.
The request supplies a scenario UUID, spacecraft identity, exact correlation and operation
profile versions, initial onboard tick, initial storage/propellant and provenance. The API
resolves the versioned sources through Mission Definition HTTP endpoints and rejects owner
ID/version mismatches, spacecraft/mission/correlation binding mismatches, ticks outside the
declared correlation interval and reservoirs exceeding pinned mission capacity.

The simulator owns the resulting scenario, immutable initial history and `SimulationScenarioCreated`
outbox event in a single transaction. Complete typed source snapshots and canonical SHA-256
fingerprints are preserved. MissionProfile is currently create-only in its owner; its complete
content is pinned rather than inventing a version number absent from that API. No other service's
database is read. A saved idempotent replay needs no further owner lookup; a different key cannot
overwrite an existing scenario identity.

ADMIN diagnostic reads use `/api/simulation/scenarios/{id}`; SERVICE reads may use
`/internal/simulation/scenarios/{id}`. Global internal-route restrictions remain unchanged.
Operator/requester access is denied. Diagnostic onboard state is not a ground execution
observation and does not grant release authority. No command is executed merely by initializing
a scenario. This is not yet a clock-advance endpoint or the spacecraft link adapter.

Four `SimulationScenarioApiIT` tests use actual Spring HTTP, PostgreSQL and RabbitMQ with
only the Mission Definition HTTP adapter mocked: exact snapshot/hash roundtrip, database
reconstruction with a fresh controller, idempotent replay without owner access, one history
and outbox event, four concurrent identical creates, role enforcement, invalid source versions,
initial tick validity and reservoir capacity. Along with the six effect tests, all ten tests
passed in `/private/tmp/msc-scenario-api.log`. This test does not replace an actual multi-service
deployment check and does not claim whole-process restart or completed command execution.

The subsequent K8s check `python3 scripts/verify-simulation-scenario.py` passed six named
checks against the actual Mission Definition and Simulator services. It reused the isolated
correlation-test mission, published matching resource evidence if absent, and verified source
snapshots, creation/read roles, durable reads, replay and duplicate/conflicting requests.
Evidence: `.local/simulation-scenario-verification.json` and
`/private/tmp/msc-scenario-live.log`. Only the Simulator Deployment was replaced; other
service images and replica settings were preserved.
