# Current simulation authority check

Control adds `POST /api/command-loads/{id}/authority-check` and its internal equivalent for
ADMIN/OPERATOR/SERVICE. On every request it retrieves current authority policy and simulation
planning model from Mission Definition, plus the current spacecraft estimate from Monitoring.
The caller supplies only the prepared load identity; policy, phase, mode, risk and approvals
cannot be supplied as request overrides.

The configured SIMULATION planning model supplies the mission phase. Actual mode for this
check comes from the accepted Monitoring frame, not the model's assumed mode. This is expressly
a simulation path: hardware telemetry is rejected. The owner envelopes must identify the same
spacecraft and positive revisions; policy/model must bind the load's mission definition. The
accepted telemetry frame must agree with its source binding. Missing owner data produces 404;
other transport failures remain errors.

After the owner round trips, Control reevaluates telemetry confidence using its current mission
clock. Stale/degraded/unknown evidence adds STALE_CONTEXT and AUTHORITY_UNKNOWN. For fresh
evidence, it checks each activity's allowed phase/mode and evaluates the owner's exact rule
using catalog operation, model phase, observed mode and catalog risk class. All resulting
requirements are combined with catalog minimum requirements; none override another.

Persisted approvals are read under the grant/revoke lock, filtered by the shared domain
predicate, and checked together with the commit deadline. The result retains policy/model/
telemetry snapshots and revisions, approval revisions, load binding and evaluation time.
It does not write a release or cache an old success. Changes in owner policy and aging telemetry
are therefore visible on subsequent calls.

This is diagnostic evidence, not a release permit. Owner revisions can change after the reads;
final release still needs revision fencing and current schedule/resource/booking/safety checks.
The use of the simulation model as phase authority is not a claim of a hardware mission-state
feed. No default mission phase or NORAD-derived authority is introduced.

The integration test uses real Control persistence and mocked owner HTTP responses with a
deterministic clock: one human cannot satisfy a two-person owner rule, two can, telemetry
expiry blocks, and a new deny-all policy blocks the same approved load. HTTP requester denial
is tested with the running Spring security context. Actual cross-service positive deployment
verification remains pending.

The focused run passed 22 tests across four classes with zero failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-current-authority-check.log`). Control was then
independently updated in the local kind cluster. All ten service Deployments remained ready,
including Planning's two replicas.

The extended `scripts/verify-command-approvals.py` passed deployed requester denial and
operator/service missing-load checks for this endpoint. The encompassing schedule, catalog
approval, human approval, preparation and persisted execution-evidence regression chain
also passed (`/private/tmp/msc-current-authority-live.log`). This proves route/rejection
behavior after deployment, not a positive multi-owner check of a production-prepared load.
