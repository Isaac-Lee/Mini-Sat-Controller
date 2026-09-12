# Backend completion plan — accepted MSA scope

Status: In progress. This is the full backend goal, not a claim of completion.

## Acceptance

A user can submit a supported target/AOI over an authenticated HTTP API, then
observe durable progress through planning, schedule commitment, approved command
compilation/release, simulator uplink/execution, telemetry reconciliation, downlink
accounting, L0/quicklook production and fulfillment. Services run in separate
processes, own their data, communicate by HTTP/events, and can be scaled separately
on the local Docker Desktop Kubernetes cluster in an isolated MSC namespace.
Real spacecraft/ground-station traffic is excluded; simulator artifacts must be
clearly distinguished from mission-qualified operational data.

## Work packages

1. Shared runtime: Java 21/Spring Boot, service-owned PostgreSQL databases,
   authenticated APIs, input validation/errors, migrations, transactional immutable
   state history, outbox/inbox, bounded retries/dead letters, health/metrics.
2. Mission definition/reference/flight dynamics: approved versioned activity and
   command catalogs, snapshot collection/validation/freshness, orbit designation,
   ephemeris/contact/imaging opportunities from explicit simulation inputs.
3. Tasking/planning/ground: request revisions/lifecycle/ownership/idempotency,
   durable pinned planning runs, prioritized opportunity selection, timeline resource
   validation, atomic versioned schedules, bookings and pass lifecycles.
4. Control/Space Link/simulation: semantic compiler, scoped approvals and synchronous
   release gate, durable send claim, duplicate suppression, UNKNOWN reconciliation,
   simulator execution/evidence and fault injection.
5. Monitoring/anomaly: out-of-order/delayed observations, projections/freshness,
   critical threshold detection, safety freeze and approved recovery handling.
6. Acquisition/product/projections: manifest/gap accounting, source preservation,
   object storage, quicklook and evidence-based request fulfillment, mission-map API.
7. Packaging/verification: independent images and K8s Deployments/Services,
   configuration/secrets/probes, measured resource settings and optional HPA,
   API contract documentation, end-to-end/restart/concurrency/retry/fault/scale tests.

## Consistency requirements

Both a spacecraft simulator and a ground-station simulator are included in work package 4.
The spacecraft side must accept approved command loads through the Space Link boundary,
execute time-tagged activities, evolve explicitly configured simulated state/resources, and
produce telemetry and payload evidence. The ground side must model reservation/contact
lifecycles, uplink/downlink delivery and reception evidence. Shared scenarios must exercise
delayed, missing, duplicate and failed delivery, disconnected periods and restart recovery.
They use the same external ports as future equipment adapters and identify all generated
observations/products as simulation data. Current telemetry injection and station allocation
mocks are incremental capabilities, not completion of either simulator's full scope.

No service reads another service's database. Schedule writes serialize per
spacecraft/horizon using database-level protection. Events are post-commit facts;
state/outbox and inbox/local effects share a transaction. Uplink cannot claim
exactly-once delivery merely from broker delivery; durable command IDs, adapter
idempotency and explicit UNKNOWN reconciliation are required. Critical interlocks
must be checked at the release boundary, not only via eventual event projections.

## Evidence still required

Do not mark this goal complete until every work package and integration gate has
current passing evidence. The original Foundation test suite alone is insufficient.
Detailed mission/numerical/instrument choices that change architecture or operations
must be surfaced, not replaced with hardcoded successful fixtures.

## Current checkpoint

Ten services are independently deployed: Tasking, Planning, Mission Definition, Reference Data,
Flight Dynamics, Ground Operations, Simulator, Monitoring, Anomaly and Spacecraft Control.
The local runtime uses the dedicated `msc-local` kind cluster and `msc` namespace, with two
Planning replicas and one replica for each other service. This is local integration and initial
replica evidence, not production capacity or full-workflow scale qualification.

[Planning runs](planning-runs.md) bind immutable request/input/catalog/model evidence to
activity-sized candidates. [Resources](planning-resources.md) evaluates the complete captured
future reservoir timeline. [Automatic illumination](planning-illumination.md) now queues new
runs transactionally, pins an explicit simulation assumption revision and records real FD
interval results. Its deployed new-request verification passed with two Planning replicas;
a conditional illumination result does not establish overall feasibility. Full AOI sensor
coverage, attitude sequences, combined feasibility and production schedule commitment remain
unfinished. [Schedule consistency](planning-consistency.md) provides the underlying atomic
repository operations, not a complete automatic commitment flow.

[Command preparation](command-preparation.md), [human approvals](command-approvals.md),
[current authority checks](current-authority-check.md) and
[current schedule checks](command-schedule-check.md) are implemented. These retain exact owner
evidence and support diagnostics; final cross-service release fencing and dispatch through
an independent Space Link service remain unfinished. [Control evidence](control-evidence.md)
receives simulator observations but still identifies unbound simulation evidence explicitly.

The spacecraft simulator has pinned scenarios, a durable command/effect ledger and reception
faults/reconciliation. [Onboard payload materialization](simulation-payload.md) now writes
command-bound synthetic raw bytes to S3 with exact size/hash and persistent retry. Ground Operations and its station simulator have durable allocation,
booking and reconciliation. Complete battery/orbit/attitude/maneuver evolution,
uplink/downlink payload delivery, acquisition, L0/quicklook and fulfillment remain unfinished.
Monitoring and Anomaly retain source-bound telemetry, freshness, latched safety incidents and
approved recovery. Their checks still need integration into final release orchestration.

[Public GP collection](public-orbits.md) tracks SPACEEYE-T1 (NORAD 63229) and uses preserved
CelesTrak mean elements for SGP4/SDP4 prediction. This does not supply hardware specifications;
two-line TLE import/export is not yet implemented. Explicit simulation contracts remain
separate from real mission qualification.

The [regression checkpoint](reactor-checkpoint-2026-09-12.md) records full-reactor evidence and
its exact source scope. Feature documents distinguish unit/DB tests from actual deployed
checks. Passing those checks does not complete the operational acceptance above; every
remaining work package stays active.
