# Planning resource reassessment

Planning can re-evaluate an immutable run against the current owned schedule heads.
`POST /api/planning/runs/{id}/resources/reassess` requires an `Idempotency-Key` and an
ADMIN, OPERATOR or SERVICE role. The equivalent `/internal/` route requires SERVICE.
The request accepts only the owned run ID, not caller-supplied schedules or resource values.

The API reconstructs the run from its stored input attempt and verifies the complete source
binding before evaluation. Within the existing spacecraft transaction lock it captures the
current time, reads all schedule heads extending beyond the resource initial state, retrieves
their pinned activity profiles and forecasts each candidate with those existing commitments.
Telemetry freshness is evaluated at this new time, and candidates starting in the past are
explicitly marked unevaluated. No newer external telemetry silently replaces the run inputs.

Each result stores the run fingerprint, `evaluatedAt`, captured schedule versions, resource
forecasts and issues. The original run and intake-time resource assessment remain unchanged.
The result, history, idempotency response and a small `PlanningResourcesReassessed` event
commit atomically. An identical request/key replays the earlier evidence; use a new key for
a fresh assessment. `GET /api/planning/resource-reassessments/{id}` and its SERVICE-only
internal equivalent read the immutable result.

This is resource evidence, not a schedule reservation, command authority or a commit permit.
Schedules can change after the assessment transaction ends. A future production schedule
commit must call the shared `PlanningResources.capture` calculation again while retaining
the spacecraft lock through successor publication, alongside all other feasibility gates.
The resource scope remains the explicit battery/storage/propellant simulation model.

Tests cover schedule changes increasing the forecast storage load, old evidence/replay
preservation, current-clock staleness and past candidates, source binding rejection, and an
outbox failure after result/history writes with successful same-key retry. Existing resource
and intake publication tests also cover the extracted capture path. Deployed HTTP checks
are implemented in `scripts/verify-resource-reassessment.py`; execution evidence is recorded
after successful runs.

The focused run passed 22 executions (ten resource calculation, four reassessment, eight
intake publication tests), with zero failures, errors or skips at 17:31:05 KST on
2026-09-12. Log: `/private/tmp/msc-resource-reassessment-tests.log`. Opus reviewed the
transaction/current-time boundary. The spacecraft lock intentionally serializes this local
calculation with schedule commits; the result has no validity lease and cannot be treated
as a reusable commit permit.

## Deployed checkpoint — 2026-09-12

Planning image `msc-planning:1b9f8638a6a532a204485e5c` rolled out with both replicas ready. The HTTP verifier passed
for run `6684d064-b2e2-3dce-83cb-76a24b8ae880`, producing assessment
`54352cc5-53a0-4b10-95fa-b2306c614230`. Its historical candidate correctly reported
`FRESH_RESOURCE_INITIAL_STATE_REQUIRED` and `CANDIDATE_START_PRECEDES_EVALUATION`
without a new forecast. The captured schedule-head list was empty in this live fixture;
changed nonempty-head accounting is covered by the DB test.

Role denial, immutable replay/readback, new-key reassessment and preservation of the original
run/resource assessment passed. Evidence: `.local/resource-reassessment-verification.json`
and `/private/tmp/msc-resource-reassessment-live.log`. These results do not establish
production schedule commitment or release.
