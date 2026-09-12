# V1 simulation schedule selection

This implements the narrowed [V1 functional scope](v1-functional-scope.md). An OPERATOR or
ADMIN selects an owned Planning candidate after reviewing its sampled camera evidence.
The selection is explicitly `SIMULATION_V1_SAMPLED_REVIEW`; it does not certify continuous
exposure, precision attitude or illumination. Original PlanningRun evidence remains immutable.

`POST /api/planning/runs/{runId}/simulation-commit` requires `Idempotency-Key` and:

```json
{
  "candidateId": "candidate-id",
  "cameraModelVersion": 1,
  "expectedScheduleVersion": 0,
  "reviewReference": "operator-review-reference"
}
```

The API checks the current request revision and local supersession fence, owned run/camera
binding, at least one computed sample, current telemetry/resource forecast, exclusive resource
conflicts and schedule version. It atomically saves the schedule, source catalog, V1 decision,
idempotent response and outbox events. Tasking consumes the assignment event to show SCHEDULED.
Command preparation and approval remain separate steps; this API does not transmit commands.

Read the decision with `GET /api/planning/simulation-schedules/{requestId}/{revision}`.
Use the existing schedule query and Control preparation APIs with its returned schedule key
and version. Request cancellation propagates asynchronously; subsequent execution must recheck
the current request before dispatch. A stored historical schedule is not cancellation clearance.

## Verification

```sh
python3 scripts/verify-planning-search.py --with-runs --with-camera \
  --with-simulation-commit --timeout-seconds 180
```

This creates a fresh synthetic mission, submits a real request, waits for automatic Planning
and camera evidence, commits the selected schedule and prepares its actual Control command
load, grants an operator approval and checks the current schedule. It verifies persisted
bindings and Tasking progress, then cancels the test request.
It does not yet execute that load or verify downstream product fulfillment.

PostgreSQL tests cover successful commit/replay, unchanged run history, schedule version and
second-decision rejection, superseded/stale inputs, and atomic rollback on outbox failure.

Focused PostgreSQL verification: 8 tests passed on 2026-09-12 at 18:38 KST, including existing
resource reassessment regressions. This is not a new full-reactor verification.

Deployed HTTP verification passed with Planning image `msc-planning:322c5b8588c2740b9902253e`
and two replicas. Request `b0016781-9f47-445d-8786-e1ce4e0e22d5` selected run
`fd0fab46-9562-3d4c-84c5-4acbbdfd0f9d`, committed schedule version 1, reached SCHEDULED
and prepared load `v1-5b8179c12afd46279ad9877751730fb5` with an operator approval.
The verifier then cancelled the request; these IDs are historical evidence, not active work.
