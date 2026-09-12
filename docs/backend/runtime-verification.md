# Backend runtime verification — 2026-09-11

Current checkpoint (2026-09-12): nine independent services including Planning intake.

## 2026-09-12 complete committed-source reactor

An immutable `git archive` of pushed commit `eefefb3a62bde20fdf83fc352335e03f26d646f0`
was tested in `/private/tmp/msc-pushed-reactor-b6k7k_3q`, independently of the working
tree where the simulator worker continued editing. The complete 17-module reactor
finished with **BUILD SUCCESS: 270 tests across 58 classes, zero failures, errors or
skips**, in 2 minutes 57 seconds. The command selected `*Test,*IT` explicitly,
used JDK 21 and the pinned Maven repository, and supplied the existing pinned Orekit
archive/digest. PostgreSQL and RabbitMQ integration tests used actual Testcontainers.
Log: `/private/tmp/msc-pushed-reactor.log`; machine-readable local checkpoint:
`.local/pushed-reactor-checkpoint.json`.

This establishes a clean full reactor for that committed implementation baseline,
superseding the earlier failure-plus-scoped-recheck evidence below. It does not
include the subsequent onboard execution worker's source or prove unfinished
operational acceptance criteria. No production image was replaced by this isolated
test run. Later documentation-only commits do not change the tested Java source.

## 2026-09-12 GP eclipse and simulation time correlation

The scoped Mission Definition / Flight Dynamics Maven reactor completed successfully:
207 tests across 40 classes, no failures, errors or skips, including explicit `*IT`
selection and the pinned Orekit reference archive. Log:
`/private/tmp/msc-gp-correlation-reactor.log`. This is scoped reactor evidence,
not completion of the full BE acceptance suite.

Both services were independently rebuilt and rolled out in `msc-local`, preserving
the other deployments and Planning's two replicas. Successful image entries were
merged into `.local/service-images.json`; rollout evidence is
`.local/k8s/gp-correlation-rollout.json`.

`python3 scripts/verify-illumination.py --with-gp` passed six sampled-target checks
and six GP eclipse checks against the deployed API. The GP checks use the existing
NORAD 63229 snapshot without refreshing the provider, verify raw SHA and source
binding, persistent SGP4/SDP4 model-bound results, requester denial/operator access,
idempotent replay/conflicts, and a complete disjoint eclipse/sunlit partition.
Evidence: `.local/gp-eclipse-verification.json` and
`/private/tmp/msc-gp-eclipse-live.log`. The partition check is not an independent
numerical accuracy proof; solar model accuracy remains uncharacterised.

`python3 scripts/verify-simulation-correlation.py` passed six named owner-API
checks: admin-only publication, replay/conflicts, stale CAS rejection, exact
historical/current reads, read roles/missing versions, and mission-binding rejection
without mutation. It creates an isolated synthetic mission and preserves its two
published versions as evidence. Result: `.local/simulation-correlation-verification.json`;
log: `/private/tmp/msc-correlation-live.log`. Conversion arithmetic is covered by
the Maven contract tests; this API test does not claim spacecraft execution.

The continuous spatial solar-elevation helper is unit-tested but is not yet wired
into temporal AOI feasibility. Simulator onboard execution, full physical state
evolution, ground delivery and the remaining operational flow remain unfinished.
The latest full default suite passes 114 tests; a subsequent six-test Planning intake
run also passes, including the new cache-publication cancellation regression; [planning input evidence](planning-inputs.md)
adds real request-event intake, pinned simulation agility models and revision/cancellation checks.
[Weather input evidence](weather-inputs.md) adds full-area/time coverage selection,
immutable synthetic cloud forecasts and cloud-limit diagnostics in Planning.
[Ground availability](ground-availability.md) adds immutable local allocation views,
exact free intervals and pinned ground input collection.
[Propellant inputs](propellant-inputs.md) add source-bound simulation mass estimates,
explicit uncertainty and loss bounds, version mismatch rejection and immutable artifacts.
[Planning consistency evidence](planning-consistency.md) adds timeline
resource and durable schedule publication checks. [Tasking/reference evidence](tasking-and-reference.md) adds real broker
resolution, source preservation, restart and two-JVM consistency checks. The
numerical checkpoint below additionally has five explicit reference/access IT tests.

[Automatic point search](planning-search.md) now passes nine live API checks including
a known synthetic Orekit access window, immutable prediction binding and approved
activity-sized options. The subsequent affected reactor passed 73 tests for simulation
model collection, narrower sensor angle, allowed phase/mode and deadline clipping. The approved
[simulation planning model](simulation-planning-model.md) has five standalone live
checks; its conservative power mapping has a battery-exhaustion regression test.

This is partial evidence for the active full-backend goal. It is not backend
completion, hardware qualification, or a Kubernetes deployment claim.

## Implemented and exercised

- Java 21 / Spring Boot 3.5.16, PostgreSQL 17.6, RabbitMQ 4.1.4,
  S3-compatible MinIO, service-owned database logins and migrations.
- Immutable state history, CAS writes, idempotency fingerprints, transactional
  outbox, publisher confirms, transactional inbox and duplicate suppression.
- Local authenticated HTTP and role checks; configurable OIDC resource-server
  and service client credentials are implemented but an external IdP is untested.
- Independent mission-definition process: approved catalog and mission profile
  creation/query. HTTP tests cover authentication, authorization, immutable
  version registration and idempotency conflicts.
- Independent flight-dynamics process: immutable Cartesian initial solutions,
  Orekit two-body propagation in EME2000, ITRF/WGS84 ground tracks, content-addressed
  S3 ephemerides, PostgreSQL prediction manifests, outbox events and streaming reads.
- Fail-closed domain command-release policy is tested but not yet connected to
  a transmitting service. Nothing in this evidence claims command execution.

## Tests

At the initial orbit checkpoint, `./mvnw verify` passed 42 tests, with no failures or skips: 31 domain/application
and release-policy tests, 7 platform tests with real database/broker containers,
1 live catalog HTTP test, and 3 analytical orbit/nanosecond-time tests.

Explicit `OrekitReferenceFramesIT` and `OrekitAccessPredictorIT` runs passed 5 tests with the pinned
reference archive: the 2016 leap second, Earth rotation/altitude consistency and
out-of-range EOP rejection, plus digest mismatch rejection. Access tests cover imaging versus contact visibility,
horizon clipping, later rising/setting passes, opposite-Earth occlusion and minimum
duration rejection. These integration
checks are intentionally invoked explicitly; default `verify` does not include IT.

`scripts/verify-flight-dynamics.py` exercises the separately running packaged
service and actual local PostgreSQL/RabbitMQ/MinIO. It verifies authenticated
input, role denial, repeated and conflicting keys, numerical prediction, S3
streamed retrieval, reference identity and stale orbit rejection. Its latest
run IDs and object references are written to ignored `.local` output. A process
restart preserved the previously generated database manifest and S3 ephemeris;
streamed bytes matched the content-addressed SHA-256 key.

## Numerical/reference limits

Orekit 13.1.8 uses an explicitly named Keplerian two-body model with WGS84 mu.
This is useful for the approved simulator path, not a real mission accuracy budget.
Predictions are limited to seven days around their initial solution and 20,000
samples. No OD/EKF, covariance fit, drag, maneuvers or operational designation is
claimed. Ground tracks reject unavailable EOP coverage. UTC conversion uses the
pinned history; future UTC dates cannot predict unannounced leap seconds.

Reference source: official orekit-data commit
`3e376b326373467647b1e246ebb083cd9e57cd68` (September 2026).
The bootstrap fetches only `tai-utc.dat` and `finals2000A.all`, checks each against
its immutable Git blob identity, computes per-file SHA-256 and writes a deterministic
zip. Archive SHA-256:
`ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1`.
Full file URLs and hashes are in the generated manifest. This is a pinned bootstrap,
not yet the reference-data service's collection/freshness lifecycle.

## Remaining full-goal work

External reference collection and orbit validation beyond the current explicit two-body
model, AOI/instrument feasibility, planning and timeline validation, ground booking, command
compilation/release and simulator reconciliation, telemetry/anomalies, acquisition,
L0/quicklook and fulfillment, mission projections, and independent K8s deployment
with measured scaling evidence remain incomplete. See the implementation plan.

## Geometric access and orbit selection checkpoint

The flight-dynamics API now persists point-imaging/contact access predictions from
Orekit continuous event roots. Results carry the immutable orbit ID, full query,
reference digest, model, 1 ms root tolerance and maximum check interval (at most
5 s, reduced to half the minimum requested duration). Search is bounded to 24 h
per request and drops windows shorter than the requested activity duration.
These are geometric candidates, not full-area coverage or scheduling permission.

An ADMIN can explicitly designate a recorded solution for its spacecraft. The
pointer uses database CAS, preserves prior versions, records the authenticated
actor/decision and applies the domain's time-regression guard. Selection rejects
cross-spacecraft references and initial states outside the model's validity window.
It is a simulator integration selection, not real mission numerical qualification.

`scripts/verify-access-predictions.py` passed against the packaged service, actual
PostgreSQL and S3: access creation/read/idempotency, role denial, wrong-spacecraft
selection rejection, and two concurrent pointer updates yielding one success and
one HTTP 409. Full default verification still passes 42 tests; 5 separately invoked
reference/access integration tests also pass, without skips.

A warm local 24-hour imaging-access request with 10 s minimum duration and 5 s
maximum event check found 14 windows in 0.373 s. This single development observation
is not a load test or production sizing evidence. The JSON measurement remains in
ignored `.local/access-timing.json`.

Local process startup now executes a SHA-256-addressed immutable JAR copy. This
fixes a verified failure where rebuilding Maven's target JAR underneath a running
Spring Boot process caused lazy class loading errors. The latest live verification
used the immutable copy and the rebuilt domain transition logic.

## Public orbit and ground-reservation checkpoint — 2026-09-11

The full reactor now passes 69 default tests; the explicit reference/access/GP
suite passes 7 additional tests. Six independent service applications are implemented.
See [public orbit verification](public-orbits.md) and [ground reservation verification](ground-booking.md)
for current evidence and limits. This is not full backend completion.

## Monitoring checkpoint — 2026-09-11

[Monitoring verification](monitoring.md) adds a seventh service, five domain tests
and four PostgreSQL tests, bringing the default suite to 78. Live Simulator/RabbitMQ
intake, historical persistence across restart and two-JVM consistency were checked.
Physical spacecraft simulation, anomaly handling and automatic planning are still pending.

## Safety and Anomaly checkpoint — 2026-09-11

[Safety/Anomaly verification](safety-and-anomaly.md) adds an eighth independent
service and nine tests, bringing the default suite to 87. Live fault/recovery,
timer-only telemetry-silence detection, Anomaly resolution history and restart
persistence passed. Command transmission and final release consistency remain pending.

## Planning cache budget and model event follow-up

The affected reactor passed 75 tests (18 classes, zero failures/errors/skips) in
`/private/tmp/msc-planning-cache-event-verify.log`. This includes zero-budget durable
cache reuse, failed fresh-HTTP budget consumption, and actual RabbitMQ delivery of
`SimulationPlanningModelPublished` to the Planning queue. Two earlier runs failed
during RabbitMQ container startup before Planning tests; the test startup budget
was increased from 60 to 180 seconds. These are test results, not proof of the
pending Kubernetes new-work check or full backend completion.

The corrected image subsequently passed nine live point-search checks with host
Planning stopped, in 20.005 seconds (explicit 300-second verification limit).
The fresh attempt was then read identically through both K8s Planning Pods;
`/private/tmp/msc-k8s-verification-updated.log` records four deployment/read checks.
All ten desired Pods were Ready after sequential recovery; host Planning was
restored with the verified JAR and readiness UP. See the independent deployment
README for the preceding CPU/startup failures and cache-bucket rollover timeout.

## Durable run follow-up

The targeted Planning suite passed 16 tests in
`/private/tmp/msc-planning-runs-test-retry.log`. After deploying the new image to
two Planning Pods, the full-input owner-API verifier passed 12 named checks in
78.1 seconds and preserved the historical run after cancelling its request.
[Run evidence and boundaries](planning-runs.md) record exact identifiers.
The candidates remain NOT_EVALUATED; whole-schedule numerical and operational
evaluation and commitment are still required.

## Planning resource assessments and K8s-only runtime, September 12

Automatic live-fenced run publication now records simulation battery/storage/propellant
assessments with current schedule heads and exact source evidence. The affected
schedule/Planning verification passed 39 tests; an Opus-review follow-up passed 28;
the representation-order follow-up passed 37. These are overlapping scoped suites,
not one combined full-reactor count. See [resource evidence](planning-resources.md)
for logs, assumptions and unimplemented gates.

The K8s-only API workflow passed 14 named checks in 82.363 seconds, including independent
reservoir arithmetic, API roles and immutable reads after cancellation. Two actual
Planning Pods returned identical input/run/resource records after fixing process-random
Set serialization order. That deployment/read verifier passed 6 checks. Planning image
`msc-planning:be4eb5bf5acd1a2bae7f5e79`; run 188b6502-f491-39cf-87d6-955399088be6.

Repeated startup/liveness failures occurred under duplicate host-JVM and K8s fleets.
The active local runtime now runs service JVMs only in K8s, preserving all databases,
broker/object data, existing API ports and unrelated projects. All 10 Pods were Ready
with 0 restarts at the final checkpoint. Local forward child-exit and stalled-tunnel
recovery were separately verified. This is not a production load/HPA/SLA claim.
Full AOI/illumination/attitude and multi-operation planning, atomic commitments,
Control/Space Link, physical simulator execution and downstream delivery remain unfinished.

## 2026-09-12 sampled target illumination owner API

After deploying Flight Dynamics image `msc-flight-dynamics:3ba147e2d65511365c500af6`,
`scripts/verify-illumination.py` passed six named checks through the local K8s API forward:
requester denial, durable operator read, identical idempotent replay, changed-body conflict,
source/scope binding, and five-point window bounds with an independently computed intersection.
Artifact `84f6ab5d-99a9-40a0-a9b1-3bb6fb50e490`, report
`.local/illumination-verification.json`, log `/private/tmp/msc-target-illumination-live.log`.
The query uses an explicitly synthetic Daejeon box and a historical reference-covered horizon.
This proves sampled-point owner API persistence and interval aggregation, not continuous AOI
illumination, eclipse on a public GP orbit, qualified solar accuracy or Planning feasibility.
The GP eclipse source extension is being implemented separately and is not in this image.

## 2026-09-12 operation integration rollout and replica proof

All nine independent images were rolled out through `.local/k8s/deploy-operation-images.py`;
Planning retained two replicas. `.local/k8s/operation-rollout.json` records each image and readiness
time; `.local/service-images.json` contains the live versions. Anomaly's startup had transient
DB connection/health latency during high local Docker CPU use; rollout subsequently completed
without relaxing probes. All ten Pods were Ready with zero restarts at the post-rollout check.

The expanded Planning owner-API verifier passed 15 checks in 245.463 seconds, and the two-Pod
comparison passed six checks. See [Planning resources](planning-resources.md) for exact artifacts
and scope. No full-workflow, sustained-load, HPA or hardware qualification is claimed.
