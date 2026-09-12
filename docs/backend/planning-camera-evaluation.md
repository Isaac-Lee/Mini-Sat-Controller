# Planning-owned automatic sampled camera evaluation

A newly published Planning run creates a durable `planning_camera_work` row in the same
transaction as its immutable run and source attempt. Independent Planning replicas claim work
with `FOR UPDATE SKIP LOCKED`, a token and a 40-minute lease. Completion is fenced by that token;
expired work can be retried. Runs superseded by a newer request revision or input attempt are
marked SUPERSEDED without contacting external owners. A bounded reconciliation of current
run publication records also recovers missing camera jobs created by older replicas during a
rolling upgrade; conflict-safe insertion cannot duplicate the job.

Before selecting a camera version, the worker requires its Planning-model version and mission
definition to match the run's pinned model. If no compatible current camera exists, work remains
WAITING_INPUTS and retries after 30 seconds. Once selected, the exact camera version is retained
across retries. Missing owners and transient failures also retry; invalid bound evidence is rejected.

The evaluator rederives the run from its owned input attempt before any remote calculation.
For every candidate, it requests Flight Dynamics pointing at the captured target over the exact
activity window, sampled at one second, then requests a camera footprint evaluation with the
full captured AOI. It verifies returned query, spacecraft, orbit, reference digest, source hashes,
model hashes and sample counts against the run. It does not accept caller-provided target vectors
or substitute current orbit/Planning inputs. A run supports 1..32 candidates and each candidate
window is bounded to 2,000 seconds by the FD evaluation sample limit.

The immutable assessment preserves the exact camera envelope, run hash and candidate-to-FD
manifest references. Metadata, history, replay and `PlanningCameraEvaluated` outbox publication
are atomic. The potentially large sample artifacts remain in FD-owned object storage.

Routes (ADMIN, OPERATOR or SERVICE; internal routes additionally require SERVICE):

- `GET /api/planning/runs/{id}/camera-work`: automatic status, pinned version, attempts and issue.
- `GET /api/planning/runs/{id}/camera/{version}`: persisted assessment state envelope.
- `POST /api/planning/runs/{id}/camera`: explicit evaluation with `{"cameraModelVersion": 1}` and
  `Idempotency-Key`. Retries return the same assessment; a changed body with the same key conflicts.

All three have equivalent `/internal` routes. Different request keys for the same run and camera
version retain a single assessment and event.

The scope is `SAMPLED_CAMERA_EVIDENCE_EXPOSURE_AND_ATTITUDE_PENDING`. No candidate is promoted
to FEASIBLE by this work. Continuous exposure coverage, attitude tracking/settling, combined
resource/safety/ground checks and actual schedule commitment remain separate required work.

Validation includes the API source-binding tests, real PostgreSQL persistence/rollback and worker
lease/retry tests, and Planning intake registration tests. The deployed automatic-flow verifier is
`python3 scripts/verify-planning-search.py --with-runs --with-camera`; it creates an explicitly
synthetic mission and checks the automatically persisted assessment and FD sample artifact before
cancelling its test request. It never manually invokes camera evaluation to create the result.


On 2026-09-12, the final related suite passed 26 executions with no failures, errors or skips
(input collection, geometry, intake, camera API, persistence and worker tests). This is a focused
check, not a new full-reactor checkpoint. The deployed verifier also passed on two Planning
replicas using image `msc-planning:38f7d09efd83ec2fe71284ef`.

Request `e983fdc4-5404-47d6-97c4-7279967828f8` produced run `e90fa22c-cbfa-347d-8a42-b46acec66bdb` and automatic camera assessment
`10e35670a9cc16f0ae47166d95f696f5b2278c182f7c94bdc82954f2b1cd5de3`, using camera version 1. The assessment work for the single candidate reached
EVALUATED without a manual camera evaluation call. The verifier checked the exact candidate
window, one-second sampling, full AOI, preserved camera/Planning model envelopes, computed
planar coverage, API roles and unchanged original run. It cancelled its test request afterward.

The first deployed attempt failed before camera evaluation because incomplete legacy fixtures
consumed the geometry budget. [Readiness ordering](planning-search.md#readiness-ordering-2026-09-12)
was corrected and the same verifier then passed; the earlier failure is not counted as a pass.
