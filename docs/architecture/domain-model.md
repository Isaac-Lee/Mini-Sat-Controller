# Domain model

## Identity and lifecycle boundaries

`msc.domain.shared.Ids` publishes distinct record types for request, spacecraft,
run, candidate, activity, assignment, acquisition, command/load, product/reception,
AOI, resource, estimate and snapshot identities. IDs cannot be blank. They are
references, not repository lookup behavior or aggregate object graphs.

`ObservationRequest` owns AOI reference, positive revision, fulfillment criteria,
optional deadline, priority, interaction preference and request lifecycle. `accept`
returns a new request and preserves the received version. Scheduling is owned by
planning; any user-facing "scheduled" status will be derived from assignments.
AUTO / ASSISTED / ADVANCED never enters `AuthorityPolicy` as authorization.

## Planning and commitment

```text
MissionIntent → ObservationRequest → PlanningRun
                                  ├─ Opportunity[]
                                  ├─ PlanCandidate[] → FeasibilityEvaluation
                                  └─ DecisionRecord[]
PlanCandidate → MissionSchedule(version N+1) → Assignment + ScheduledActivity
```

PlanningDataSnapshot requires exact versioned references for orbit, agility,
propellant, spacecraft state, weather, leap seconds, EOP, station schedule,
mission definition and policy. FeasibilityEvaluation is an immutable output value,
not an aggregate root; the fixture's positive result is not physical validation.
PlanningRun defensively copies collections and checks candidate/run/request and
decision/candidate membership. Every candidate retains its run ID.

PlanCandidate creation requires an approved ActivityDefinition allowed in the
provided mission phase/mode. It copies the activity definition ID/version and
exclusive resource profile into ScheduledActivity. It cannot invent a command
sequence. Approval catalog loading, parameter schemas and safety validation are
future work; the boolean approval flag is trusted fixture/catalog data, not an
authentication mechanism.

MissionSchedule identity is `(spacecraftId, planningHorizon, version)`. Its constructor
is private; `empty` creates version zero, and `commit` returns the next version.
Assignment references request/candidate/run/activity by typed ID. Provenance is
traversable through the run and pinned snapshot references. Future durable
publication must retain the run/input artifacts alongside the schedule references;
v0.1 returns the run to the caller and keeps schedule history only in memory.

Implemented commit invariants:

- Same spacecraft and activity wholly inside the half-open planning horizon.
- Feasible proposal, unique assignment/candidate/activity identities.
- No intersection of occupied intervals for the same exclusive resource.
- No new activity before the prior frozen boundary; frozen boundary cannot regress.
- Defensive immutable collections; new version leaves old objects untouched.
- Atomic expected-version publication through ScheduleRepository; stale writers fail.

The in-memory adapter synchronizes publication across callers and retains versions.
It only accepts an extension of the current history. It is a single-process proof,
not a database transaction or multi-process scheduler lease. Horizon partitions
are independent; non-overlapping horizon ownership must be specified before live
scheduling. Automatic merging/retry of stale planning inputs is intentionally absent.

v0.1 replanning appends activities. Replacement, preemption, expiration, exact frozen
horizon replacement semantics, prioritization and optimization remain future work.
ResourceValidation prepares whole-timeline reservoir evidence and tentative/confirmed
external reservations. Any schedule change resets this evidence to NOT_EVALUATED.
Power/storage/thermal/momentum/propellant validation must simulate the timeline,
never substitute independent per-request reservations. No command release can treat
a fixture commit or NOT_EVALUATED as operational readiness.

## Commanding and safety

CommandLoad refers to a schedule key/version and contains semantic CommandInstances,
onboard time tags with a shared TimeCorrelationId, mission-definition version,
checksum, optional authorization evidence reference and a TAI commit deadline.
It rejects duplicate commands and mixed time correlations. Construction does not
compile packets, sign, authorize, transmit or execute a command.

Future CommandLoadCompiler resolves ScheduledActivity definitions into approved
command templates; command parameter/precondition checks, load validation, authority
approvals, signatures and safety interlocks are synchronous release gates. A freeze
must stop new automatic releases. `PlanningFreezePolicy` currently evaluates safe
mode or critical active anomalies for the target spacecraft; the caller supplies
active anomalies. Absence of a freeze does not grant permission.

AuthorityPolicy separates ActionClass × MissionPhase × SpacecraftMode × RiskClass
from interaction preference and returns AUTO_ALLOWED, POLICY_APPROVAL, HUMAN_APPROVAL,
TWO_PERSON_APPROVAL or AUTO_FORBIDDEN. No production authority engine exists.
MissionPhase supports LEOP, COMMISSIONING, ROUTINE, CONTINGENCY and EOL.
ContingencyProcedureRef will reference approved recovery procedures; no autonomous
contingency recovery is implemented.

TransmissionRecord records transmission, not execution. VerificationRecord preserves
observed/received times and evidence. OnboardScheduleModel is ground-side belief:
COMMITTED, APPROVED, LOADED, EXECUTION_BELIEVED, CONFIRMED, FAILED and UNKNOWN remain
distinct. Reconciliation detects different observed/believed load IDs or returns
UNKNOWN for missing evidence. It does not turn a matching load ID into confirmed
execution. Reconciliation currently compares supplied observations only; stale
verification ordering and automatic divergence-event emission remain future work.

## Observations and estimates

TelemetryObservation preserves observedAt, receivedAt, source, quality, value and
unit without assuming arrival order. SpacecraftOperationalState lives under
`msc.projections.monitoring`: mode, as-of time, freshness and evidence describe a
read model, not an authoritative aggregate. Power, thermal, storage, payload and
propulsion subprojections can be added when actual projection logic exists.

OrbitSolution and PropellantEstimate contain immutable EstimateContext: TAI epoch,
reference frame, uncertainty/covariance reference, model/configuration version and
provenance snapshot. OrbitEphemeris contains prediction coverage and a samples
manifest, not an observation. OperationalDesignation selects an OrbitSolution ID;
`select` creates a new pointer version and leaves solutions unchanged. It rejects
wrong spacecraft and regressing designation time. Durable designation changes
will require their own atomic application publication gate.

OrbitObservation, operational propellant designation/budget, attitude/agility model
and profile, maneuvers/Burn/ManeuverCalibration and FlightEventPrediction are future
Flight Dynamics boundaries. Their numeric implementation requires a separate ADR.
The same immutable estimate + designation pattern applies to propellant and time
correlation; it is not necessary to create unused generic aggregate machinery now.

## Acquisition and products

Acquisition connects request, assignment, scheduled activity, optional command load,
execution belief, receptions, data completeness and L0 products by typed IDs.
Completeness and execution outcome are independent: an execution confirmation does
not prove downlink completeness. Onboard payload manifests and as-commanded/evidence
history can be added by reference when accounting is implemented. No binary fields
exist in the aggregate.

DownlinkReception will provide source segment/packet manifests and gap accounting.
L0Product contains instrument-source manifest, packet index, gap/quality and ancillary
references. L0 preserves reconstructed time-ordered instrument information losslessly,
removing only communication-layer artifacts; instrument headers are not arbitrarily
stripped. Onboard compression normally remains unless a mission definition says
otherwise. No final format is selected.

ProductionJob will own production attempts and input/output references. Quicklook
will link a preview, coverage/cloud evidence and L0 provenance to later fulfillment
evaluation. L0 creation alone does not fulfill the user's request. These behaviors
are documented extension points, not empty classes or fake image processing.

## Mission map projection (future UI contract)

MissionMapProjection must combine spacecraft position with estimate epoch/frame,
predicted ground track and prediction version, requested AOI, tentative/committed
acquisitions, expected footprint with provenance, mission timeline, and freshness/
UNKNOWN indicators. Believed execution must remain distinct from confirmation.
Display UTC as primary and automatically detected user-local time as secondary,
with time always visible. The default interaction asks only for an observation
target; advanced constraints are hidden initially. No map UI is implemented.
