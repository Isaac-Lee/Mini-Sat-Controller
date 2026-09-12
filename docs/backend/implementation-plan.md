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
booking and reconciliation. [Payload downlink](simulation-downlink.md) now binds a payload
to a pending command and reservation, verifies executed drain and actual bytes, and records
a receiver receipt; the deployed fault/reconciliation flow passes. Complete battery/orbit/attitude/maneuver evolution,
uplink/downlink payload delivery, acquisition, L0/quicklook and fulfillment remain unfinished.
Monitoring and Anomaly retain source-bound telemetry, freshness, latched safety incidents and
approved recovery. Their checks still need integration into final release orchestration.

[Public GP collection](public-orbits.md) tracks SPACEEYE-T1 (NORAD 63229) and uses preserved
CelesTrak mean elements for SGP4/SDP4 prediction. This does not supply hardware specifications;
[Derived two-line TLE export](public-tle-export.md) is now deployed and verified for NORAD 63229;
provider-original TLE collection and caller TLE import remain pending. Explicit simulation contracts remain
separate from real mission qualification.

The [regression checkpoint](reactor-checkpoint-2026-09-12.md) records full-reactor evidence and
its exact source scope. Feature documents distinguish unit/DB tests from actual deployed
checks. Passing those checks does not complete the operational acceptance above; every
remaining work package stays active.


### Acquisition deployed source checkpoint (2026-09-12)

Simulator receipt events now enqueue immutable, hash-pinned Acquisition source imports
through RabbitMQ and a PostgreSQL work queue. The independent Acquisition Deployment
stores verified bytes in `msc-acquisition`; see [source evidence](acquisition-simulation-source.md).
Real command-to-downlink-to-source flows passed with one and two Acquisition replicas.
Gap accounting, L0, quicklook, product quality, projections and fulfillment remain open;
this checkpoint does not close the complete acquisition/product workstream.


### Automatic source completeness checkpoint (2026-09-12)

Expected downlink plans now form an Acquisition-owned immutable manifest. Verified source
arrival atomically updates its missing-source list and emits simulation completeness once.
The deployed two-replica flow passed pre-reception INCOMPLETE to automatic COMPLETE;
see [manifest evidence](acquisition-simulation-manifest.md). Packet-level gaps, independent
Product processing, L0/quicklook and request fulfillment remain unfinished.


### Independent Product source package checkpoint (2026-09-12)

Product now consumes simulation completeness events through its own durable queue, copies
and verifies sources via Acquisition HTTP APIs, and retains raw files plus an evidence index
in its own S3 bucket. The 12th service was deployed and actual RabbitMQ-to-MinIO generation
passed; see [Product evidence](simulation-source-product.md). This does not complete L0,
quicklook, packet reconstruction, product quality decisions or request fulfillment.


### Product content and byte preview checkpoint (2026-09-12)

Product now serves owned raw sources and index downloads and creates a bounded PNG byte
preview after whole-source integrity verification. Deployed API checks compared all 65,536
preview pixels with the source and verified roles/idempotency; see [content evidence](simulation-product-content.md).
This diagnostic synthetic preview does not complete a mission-qualified imagery quicklook,
coverage/cloud assessment or request fulfillment.


### Required target-pointing checkpoint (2026-09-12)

Flight Dynamics now computes and persists owner-bound required line-of-sight profiles for
Cartesian and public GP inputs. Thirteen contract/numerical tests and ten PostgreSQL API
tests passed; the deployed NORAD 63229 HTTP flow passed independent WGS84 geometry,
source preservation, role and idempotency checks. See [API evidence](required-target-pointing.md).
This supplies geometry for the next explicit simulation camera/footprint model; AOI coverage,
attitude feasibility, schedule commitment and release remain required and unfinished.


### Published simulation camera model checkpoint (2026-09-12)

Mission Definition now owns explicit camera half-angles, raster dimensions, synthetic stare
orientation law and acceptance bounds, pinned to an exact simulation Planning model version.
Ten real HTTP/DB tests and the deployed version/role/replay verification passed; see
[camera model evidence](simulation-camera-model.md). The independent numerical projection
helper also passed six tests. Owner-bound FD evaluation and Planning coverage integration
remain unfinished; publishing the camera does not promote a feasibility gate.


### Current-schedule resource reassessment checkpoint (2026-09-12)

Planning can re-evaluate an immutable run's resource inputs under the spacecraft lock using
current schedule heads and current-time freshness. Twenty-two resource/intake/reassessment
tests and deployed HTTP checks passed with two Planning replicas ready; see
[reassessment evidence](planning-resource-reassessment.md). Results are immutable evidence,
not reservations or commit permits. Production commitment must reuse the calculation
inside its own publication transaction and still satisfy the remaining feasibility gates.


### Owned sampled camera evaluation checkpoint (2026-09-12)

Flight Dynamics now computes sampled footprints from an owned pointing result and exact camera/
Planning model versions, retaining the model envelopes and bulk evidence in its S3 bucket.
Sixteen PostgreSQL API tests and six numerical tests passed, followed by deployed HTTP/MinIO
verification with independent ray-plane arithmetic over 12 samples. See
[camera evaluation evidence](camera-footprint-evaluation.md). Planning must next bind these
sources to its run and establish exposure-duration/attitude evidence before approving coverage;
actual combined feasibility, schedule commitment and command release remain incomplete.


### Automatic Planning camera evidence checkpoint (2026-09-12)

New Planning runs now queue exact-version camera evaluations. Workers retain the selected
version across retries, fence claims across replicas, wait for compatible models and recover
jobs missed during rolling upgrades. Candidate windows, AOI and owner hashes are checked
before immutable assessment publication. Twenty-six related test executions and deployed
automatic HTTP verification on two replicas passed; see [camera integration](planning-camera-evaluation.md).
A real test also exposed and corrected geometry-budget starvation from incomplete old fixtures.
Continuous exposure, full attitude sequence, combined feasibility and actual schedule commitment
remain required; this checkpoint does not promote candidates to FEASIBLE.


### User-approved V1 scope adjustment (2026-09-12)

The user narrowed completion to a first version where all main functions can be exercised.
[v1-functional-scope.md](v1-functional-scope.md) now defines acceptance and supersedes older
entries that treated continuous exposure/precision attitude work as immediate blockers.
Prioritize one connected request-to-synthetic-product workflow using the existing FD algorithms,
with guided review and visible approximation limits. Finish schedule/Control/request-product
integration, verify the functional path, then stop expanding numerical or hardening scope.

### V1 selected schedule integration (2026-09-12)

Added an explicit simulation selection API that commits an owned candidate with sampled camera
and current resource evidence, preserving the original unevaluated run. Request revision,
supersession, schedule conflicts, versioning and atomic outbox writes remain enforced; a committed
selection clears any in-flight Planning lease. Tasking receives the assignment progress event.
The guided search verifier now optionally prepares a real Control load from this saved schedule.
See [V1 schedule API](simulation-schedule-v1.md). Downstream release, simulator execution and
product-to-request fulfillment remain the next integration slice, not completed by preparation.
