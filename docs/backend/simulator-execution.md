# Simulator onboard execution

Status: completion-effect arithmetic, persistent scenario initialization, load receipts and
modeled command completion with clock advancement, and explicit simulated delivery/reconciliation
are implemented. Control/Space Link integration and full physical state/product evolution remain
in progress. This is
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
ground-observed command `EXECUTED` assertion. The executor persists the effect
and command ledger together, with duplicate suppression and ordered completion.

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

## Command loads and deterministic completion

SERVICE `POST /internal/simulation/scenarios/{id}/loads` accepts the existing semantic
`CommandLoad` plus an exact catalog reference for each command ID. It resolves catalogs through
Mission Definition HTTP and pins their content/hash in the ledger. Templates, parameters,
operation profiles, mission/correlation identity and schedule horizon must match. Duration is
converted from the decimal catalog seconds to an exact integer tick count; unrepresentable
fractions and arithmetic overflow are input errors. Both start and completion must lie within
the pinned correlation validity interval. The load must arrive before its commit deadline in
simulation time and cannot introduce a command in the scenario's past.

The current model explicitly permits only one activity at a time per scenario. Overlapping
half-open command intervals and command IDs reused across loads are rejected. At most 100
commands per load and 500 commands per scenario bound each transaction's work; larger scenarios
require another scenario, not silently skipped commands. These are simulation implementation
limits, not satellite hardware specifications.

Identical load retransmission returns its immutable original receipt, including after clock
advancement or with a new idempotency key. A canonical submission fingerprint detects changed
content under the same load ID. The caller's artifact checksum remains a traceability field;
this adapter does not claim to verify encoded uplink bytes or release authority. SERVICE
authentication and an authorization-reference string do not replace the pending Control gate.

ADMIN `POST /api/simulation/scenarios/{id}/advance` supplies `expectedVersion` and `targetTick`.
Scenario locking serializes submission and advancement. Due commands are ordered by completion
tick, load ID and command ID. The calculator applies the declared IMAGE/DOWNLINK effects at
completion, and the scenario reservoirs, clock and affected command-ledger revisions commit
together. Failure rolls back all those writes and the idempotency record. The model does not
claim progressive physics between clock instants.

The ledger distinguishes `PENDING`, `EFFECT_APPLIED`, `REJECTED` and `NOT_SUPPORTED`.
SERVICE diagnostic GET on `/internal/simulation/scenarios/{id}/loads/{loadId}` returns current
onboard facts. Submission and advancement publish no `SpacecraftExecutionObserved` event:
delayed/lost acknowledgments and explicit ground delivery/reconciliation remain to be connected.
Reading the diagnostic ledger is not a ground acknowledgment. No new payload bytes, battery
evolution or maneuver physics are claimed by these completion facts.

The expanded focused Maven run passed 13 tests (six calculator tests and seven HTTP/DB tests),
log `/private/tmp/msc-command-api.log`. New tests exercise no pre-completion effect, one applied
effect across replay and further advancement, original receipt retention after completion,
fresh-controller DB reconstruction, overlap/off-grid/overflow rejection, and injected scenario
write failure rolling back ledger updates. Full-process restart and actual ground delivery
are not established by these tests.

The subsequent deployed verifier `scripts/verify-simulation-command.py` passed six checks
against actual Mission Definition and Simulator APIs, including pre-completion state, exact
declared IMAGE resource change, replay and absence of repeat effects. A real Kubernetes
`rollout restart deployment/simulator` then replaced the process. Running the verifier with
`--after-restart` passed three further checks: identical scenario state, identical command
ledger, and no duplicate effect after another clock advance. Logs are
`/private/tmp/msc-command-live.log` and `/private/tmp/msc-command-restart-live.log`;
local evidence files are `.local/simulation-command-verification.json` and
`.local/simulation-command-restart-verification.json`. This establishes restart persistence
for the completed modeled IMAGE case, not crash-at-every-instruction proof or ground delivery.

## Delivery faults and reconciliation

ADMIN `POST /api/simulation/scenarios/{id}/loads/{loadId}/link` configures versioned link
state: `connected`, `acknowledgmentLost`, and `notBeforeTick`, with provenance and CAS.
SERVICE `POST /internal/simulation/scenarios/{id}/loads/{loadId}/receive` makes an explicit
`ACKNOWLEDGMENT` or `RECONCILIATION` attempt. Missing configuration, disconnection, delay,
lost acknowledgment or still-pending execution returns `UNKNOWN` with an explicit reason.
Reconciliation can recover a lost acknowledgment only when connected, no longer delayed,
and the entire load is terminal. No lack of response is interpreted as command failure.

Successful reception stores one immutable `SimulationExecutionContracts.Observation` and
one `SpacecraftExecutionObserved` outbox event atomically. The payload identifies SIMULATION,
scenario, spacecraft, load, exact ledger version/hash and per-command modeled outcomes/catalog
hashes. Observation and reception times are explicitly simulation TAI times; the outer event
timestamp records publication creation in the service clock. The outcome is `OBSERVED` modeled
evidence, not a physical execution confirmation or fabricated sensor reading.

Clock advancement still emits no execution observation by itself. Further reception attempts
reuse the already received observation, even after a later disconnection. Each idempotency key
identifies one attempt: replay of an old UNKNOWN attempt remains UNKNOWN; a new attempt uses a
new key. Reception does not execute commands, change their effects, or erase payload content.

The three new HTTP/PostgreSQL/RabbitMQ tests cover lost acknowledgment followed by reconciliation,
disconnection/delay gating and database reconstruction, one observation/event across retries,
and rollback of observation/history/idempotency when outbox insertion fails. The expanded focused
run passed all 16 tests (`/private/tmp/msc-reception-api.log`). External Mission Definition is
mocked in these tests. The existing broker routes carry the new event to Control/Monitoring
queues, but an operational Control receiver and release gate are not implemented yet; this
does not prove end-to-end ground operational confirmation or physical RF behavior.

After deploying the updated Simulator image, `python3 scripts/verify-simulation-reception.py`
passed seven delivery-fault checks against actual services (and first ran the six-check modeled
command fixture). It verified disconnected/delayed/lost responses remain UNKNOWN, reconciliation
returns the pinned modeled observation, repeated reception preserves it, an old attempt's replay
does not change retrospectively, and reception never reapplies resource effects. Evidence:
`.local/simulation-reception-verification.json`, log `/private/tmp/msc-reception-live.log`.
