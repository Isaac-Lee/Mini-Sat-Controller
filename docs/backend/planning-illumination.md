# Planning candidate illumination evidence

Planning accepts `POST /api/planning/runs/{id}/illumination` with an exact positive
`assumptionsVersion`, for ADMIN/OPERATOR/SERVICE. It loads an existing immutable Planning run,
verifies captured geometry/model hashes, and retrieves the selected Mission Definition solar
assumptions. Mission identity/version and the geometry's reference digest must agree.

For each of 1..32 candidates it builds an FD interval query from the recorded activity window,
captured AOI and simulation model's minimum solar elevation. Altitude comes explicitly from
the owner's assumption extent; Tasking's two-dimensional AOI does not invent terrain elevation.
The assumption scope must cover each query. Candidate count bounds remote work per evaluation.

FD is called through `/internal/solar-intervals` with a content-derived idempotency key. Its
response must retain the exact query and selected owner envelope/hash, a valid result identity,
and one of the two conditional outcomes. Mismatched source/query responses are rejected. The
assessment retains the complete FD records with candidate identities and the original run hash.

Local assessment/history, idempotency response and PlanningIlluminationEvaluated outbox event
are written atomically after remote computation. An existing run/version result cannot be
replaced by different content. A same-key retry returns before owner I/O. API/internal
`GET /planning/runs/{id}/illumination/{version}` reads the resulting immutable assessment.
Remote FD results can already exist if a later candidate fails; they remain idempotent owner
artifacts and no partial local assessment is published.

Neither conditional outcome changes overall candidate feasibility or commits a schedule.
The remaining AOI sensor, attitude, resource, booking and live safety/conflict gates still apply.
The endpoint also supports operator/service-invoked evaluation. Automatic scheduling is described
below; the final combined feasibility gate and schedule commit path remain to be connected.

Six focused tests across the options, run and illumination suites passed with no failures/errors/
skips (`2026-09-12`, `/private/tmp/msc-planning-illumination.log`). The two new tests mock owner
HTTP/store boundaries and verify candidate-derived queries, conditional evidence retention,
wrong owner revision/query rejection and unchanged overall feasibility. They do not establish
HTTP authorization, DB transaction behavior or deployed cross-service evaluation for this API.

A subsequent PostgreSQL 17.6 Testcontainers check passed four illumination tests across two
classes (`2026-09-12`, `/private/tmp/msc-planning-illumination-persistence.log`). The two
persistence tests use real migrations and transactions with mocked owner HTTP. They prove
assessment readback, same-key replay after controller reconstruction without owner calls,
changed-request key conflict, different-key deduplication of history/outbox, and rollback of
assessment/history/idempotency when outbox insertion fails, followed by successful retry.
JSON readback is compared using canonical fingerprints because Jackson integer node widths
can differ after PostgreSQL JSONB round trips. This adds DB transaction evidence; HTTP role
checks and deployed cross-service evaluation remain separate verification requirements.

## Deployed service verification

On 2026-09-12, Planning image `msc-planning:0aa1906ed837b11212917af7` rolled out
successfully with both replicas ready. All ten service Deployments remained ready.
`scripts/verify-planning-illumination.py` passed eight check groups against the existing
synthetic search run `557f3d6f-b68b-36a4-b7b8-f02ed6789bc0`.
It used the stored candidate window, AOI, model and reference digest, published explicitly
test-only assumptions for that synthetic spacecraft, and exercised actual Planning → Mission
Definition → Flight Dynamics HTTP calls. Owner interval records and Planning assessments
were read back, both same-key and different-key retries returned the immutable assessment,
changed-request reuse returned 409, and requester compute/read returned 403. The original
run remained byte-equivalent as parsed JSON, including unevaluated overall feasibility.

The candidate returned `NOT_ESTABLISHED`: these declared bounds do not establish the required
illumination, rather than proving darkness or observation infeasibility. This verification
is not physical qualification of the assumptions. Evidence is stored locally in
`.local/planning-illumination-verification.json` and
`/private/tmp/msc-planning-illumination-live.log`. The verifier only accepts the synthetic
search spacecraft prefix and does not modify NORAD 63229 mission assumptions.

## Automatic evidence work

New runs published by PlanningIntake now enqueue `planning_illumination_work` in the same
transaction as the input attempt, run, resource assessment and outbox. V4 adds the queue;
existing historical runs are not backfilled. The scheduled worker claims one due item with
PostgreSQL `FOR UPDATE SKIP LOCKED`, allowing independent Planning replicas to compete.
It excludes work superseded by another input attempt or invalidated request revision.

The worker selects the current Mission Definition assumptions once, validates owner identity
and revision, and persists that exact revision before requesting evaluation. Recovered work
uses the pinned revision, never a silently newer owner version. A 40-minute lease permits the
bounded candidate evaluation; expired leases are reclaimable. Completion/status writes require
the same live token, and API idempotency plus immutable assessment identity protect repeated
computation after a crash. Network calls occur outside queue transactions. An already-running
superseded worker may finish immutable historical evidence, but cannot change a replacement's
queue state or commit a schedule.

Missing assumptions or transient failures wait 30 seconds before retry. Invalid local evidence
is rejected. No default assumptions are invented. `EVALUATED` means evidence was recorded,
including a possible `NOT_ESTABLISHED` outcome, not that a candidate is feasible.
`GET /api/planning/runs/{id}/illumination-work` and its `/internal` equivalent expose status,
pinned revision, attempt count and an issue code to ADMIN/OPERATOR/SERVICE. Pre-feature runs
without automatic work return 404; their manual evaluation endpoint remains available.

The new worker is source-only until its subsequent deployment verification. PostgreSQL tests
cover automatic completion, no duplicate processing, pinned-version recovery, expired-token
fencing, superseded-input exclusion and transient-owner retry without fabricated assumptions.

Focused validation passed 18 test executions across four classes with no failures/errors/skips
on 2026-09-12 (`/private/tmp/msc-planning-illumination-worker.log`); this includes the two
persistence lifecycle cases inherited by the worker test fixture. The intake test also checks
that a successfully published run has a queued illumination item. These direct worker tests
use actual PostgreSQL and mocked HTTP owners; they do not yet prove scheduled execution in K8s.
