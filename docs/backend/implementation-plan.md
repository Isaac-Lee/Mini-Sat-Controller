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

[Independent Control intake](control-evidence.md) is now the tenth deployable service.
Simulator-delivered observations reach its owned database through RabbitMQ and retain explicit
unbound simulation status. Command preparation/release and Space Link execution remain pending;
the existing schedule events are durably deferred for that workflow.

Shared runtime, mission-definition, initial Orekit flight-dynamics, tasking and
approved geographic reference services are implemented and exercised. [Runtime evidence](runtime-verification.md) records
114 tests in the latest full run and a subsequent six-test Planning intake recheck,
7 earlier explicit reference/access/GP integration tests and live API/S3/restart
checks. Geometric access prediction and versioned orbit selection now provide
inputs for planning; full AOI/weather/resource feasibility remains pending. [Local run instructions](local-runtime.md) reproduce these checks. All
remaining work packages above stay active; this checkpoint is not full completion.

[Tasking/reference evidence](tasking-and-reference.md) includes cross-service resolution,
request lifecycle and two-JVM consistency checks. The planning queue now receives
accepted, version-pinned requests. The independent Planning service now collects
immutable orbit, agility, weather, ground allocation and propellant inputs; actual
[automatic point geometry search](planning-search.md) now produces pinned evidence; [durable run/candidate recording](planning-runs.md) is now connected; complete
candidate evaluation and schedule orchestration remain pending.

[Planning consistency evidence](planning-consistency.md) now covers whole-timeline
reservoir calculations, validated schedule reconstruction/withdrawal and PostgreSQL
schedule publication. Automatic point search is connected; full candidate validation and commitment in the Planning service remain pending.

[Public GP orbit collection](public-orbits.md) now tracks user-selected SPACEEYE-T1
(NORAD 63229), preserves source snapshots and uses SGP4/SDP4 for prediction.
[Ground Operations and station Simulator](ground-booking.md) add two independent
services and durable reservation reconciliation. Full automatic planning, control,
telemetry/payload simulation and product delivery remain pending. Independent
K8s packaging and initial replica/read checks exist; sustained availability and
full-workflow scale verification remain pending.

[Monitoring](monitoring.md) adds durable source-bound telemetry evidence,
observation-time ordering/freshness, immutable estimate history and a seventh
independent service. Simulator supports explicit telemetry scenario injection over
the shared outbox/broker path; physical vehicle evolution remains pending.

[Safety and Anomaly](safety-and-anomaly.md) adds persisted critical incidents,
latched freezes, current-evidence checks, scoped operator recovery and a distributed
watchdog. It is an eighth independent service. The final cross-service release
consistency boundary and actual Control/Space Link dispatch remain pending.

[Independent deployment](../../deploy/k8s/README.md) now packages nine service
images and Deployments. Local kind recovery restored ten Ready Pods (Planning two,
all others one) with zero restarts after sequential startup. This recovery follows
an observed shared-resource startup/restart failure; it is not production capacity
evidence. The latest Planning cache-budget/model-event reactor passed 75 tests.

Latest integrated evidence: commit `eefefb3` was exported to an isolated directory and the
complete Maven reactor passed on 2026-09-12: **270 tests across 58 classes, zero failures,
errors or skips**, with explicit `*Test,*IT` selection, Docker-backed persistence/broker tests
and the pinned Orekit reference archive. Log: `/private/tmp/msc-pushed-reactor.log`.
This supersedes the earlier 233-test run with a test-only numeric-node assertion failure and
its scoped rechecks. Subsequent changes before this checkpoint affect documentation only.

Operation-aware Planning verification and both-replica readback have passed. Mission Definition
and Flight Dynamics were subsequently independently deployed with GP eclipse and simulation
clock-correlation support; deployed API verifiers passed 12 illumination/GP checks and six
correlation ownership/history checks. See [runtime evidence](runtime-verification.md).
These results cover the implemented baseline, not the remaining full operational requirements.
The onboard execution ledger is being implemented separately and is not part of this reactor
snapshot; physical simulator evolution, full AOI/attitude feasibility, atomic operational
commitment, Control/Space Link and product delivery remain unfinished.
