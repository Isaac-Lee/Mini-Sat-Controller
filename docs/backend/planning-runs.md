# Durable Planning runs and candidates

Input-attempt publication now records a `PlanningRun` for each asset that has all
ten required input categories, a pinned activity catalog, simulation model,
point-geometry evidence and activity-sized options. Missing categories produce no
run; the attempt remains the authoritative record of missing inputs. Capturing
all categories does not mean that every input is suitable or every gate passed.

Each published run additionally binds the Tasking request revision, original input
attempt ID, spacecraft, all source hashes, exact catalog/model evidence and
geometry evidence. Domain candidates retain the approved definition/version,
phase/mode, resources and time window. Run/candidate/activity IDs are derived from
the immutable attempt and option; retrying the same completed lease cannot append
a second run. Separate input attempts remain separate historical executions.

The domain input references identify version-one **captured evidence** by content
hash. They do not invent owner API versions: exact owner IDs/versions remain in
the immutable input attempt and the separately retained catalog/model/geometry.
Hashes are verified before run publication. No latest-data lookup occurs while
building a run from its captured evidence.

`planning-input-attempt`, geometry cache entries, `planning-run`, the per-attempt
run index, per-run resource assessments, and `PlanningRunRecorded` outbox events share the live-lease transaction.
Once Planning ingests cancellation or revision invalidation, its local fence blocks
the entire publication. This is not a distributed transaction with Tasking: a later
schedule/release path must revalidate current request state at its own boundary. Recording a
run neither changes Tasking to scheduled nor writes a mission schedule. Previously
stored attempts are not rewritten; their run index is empty if none was recorded.

## Read APIs

Both public and internal routes allow ADMIN, OPERATOR or SERVICE:

- `GET /api/planning/runs/{id}` (internal equivalent `/internal/planning/runs/{id}`)
- `GET /api/planning/input-attempts/{id}/runs` (same internal prefix)
- `GET /api/planning/runs/{id}/resources` (same internal prefix; new assessments only)

The latter returns `inputAttemptId` and `runIds`. The former returns the source-bound
wrapper and serializable domain run. Requester access to these detailed operational
records is denied; the existing owner-filtered request status API remains available.

## Evaluation boundary and verification

Candidates currently remain `NOT_EVALUATED`, with pending gates and captured
missing-input reasons in decision records. Separate [resource assessments](planning-resources.md) now compute simulation battery/storage/propellant from pinned telemetry and current schedule context. This does not establish AOI illumination, whole-attitude validity, ground confirmation or release permission. Multi-operation resource models, fully evaluated candidates and schedule commitment remain required follow-up work.

The targeted tests exercise request revision/source retention, JSON restoration,
missing-category handling, hash mismatch rejection, cancellation fencing,
transactional index/run/event publication and duplicate completion. Run actual
owner-API verification with:

```sh
python3 scripts/verify-planning-runs.py --timeout-seconds 300
```

This provisions explicitly synthetic telemetry, propellant, weather and policy
inputs alongside the existing synthetic point-search fixture. It verifies durable
run/candidate provenance and API roles, and confirms that request cancellation
preserves historical run data. Operator enable remains required; the fixture does
not claim safety release or hardware feasibility. Record passing evidence only
after deployment and execution.

The targeted Planning suite passed 16 tests in
`/private/tmp/msc-planning-runs-test-retry.log`. An earlier fixture equality check
compared in-memory JSON long/int node classes; the persistence assertion now checks
canonical serialized content, while explicit revision and source assertions remain.
The new Planning image was deployed to two K8s replicas. With host Planning
stopped, the owner-API verifier passed 12 named checks in 78.1 seconds, plus the
post-cancellation historical-read assertion. Request
`0b0d8ee3-8110-4735-8f99-2d1f6ac2c518` produced input attempt
`5afc3427-2597-4fe6-9fc4-9fc11de61736` and run
`0de4cf36-bbb5-3791-be02-18b1b8e12eb0`. The request was cancelled during cleanup;
the run remains an immutable, unevaluated historical record. Host Planning was
restored with the verified JAR and returned readiness UP.

Evidence is in `.local/planning-search-verification.json` and
`/private/tmp/msc-k8s-planning-workflow-retry.log`. This confirms run recording,
not resource computation, schedule commitment or full backend completion.

The expanded `verify-k8s.py` then passed five deployment/read checks, including
identical retrieval of both the input attempt and its linked run through each
Planning Pod. `.local/k8s/verification.json` records Pod UIDs/image IDs and the run.
