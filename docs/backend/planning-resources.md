# Planning resource assessments

New input-attempt publication records an immutable resource assessment for each recorded run in the same live-claim transaction. GET `/api/planning/runs/{id}/resources` (or `/internal/planning/runs/{id}/resources`) allows ADMIN, OPERATOR and SERVICE. Old runs are not rewritten and return 404 for absent assessments.

Assessment metadata distinguishes `CURRENT_HEADS_CAPTURED` from `SCHEDULE_HEADS_UNREAD`; an unread schedule context cannot produce a forecast. Catalog hash failures and activity binding mismatches have separate integrity issue codes. The load set must cover every included activity.

The assessment is a simulation forecast of battery, storage and propellant. It does not authorize an assignment, confirm ground capacity, or replace full candidate feasibility. Candidate feasibility remains NOT_EVALUATED. Missing or invalid evidence leaves the forecast absent with explicit issues; a computed forecast has VALIDATED or REJECTED resource status.

The initial epoch is the accepted Monitoring frame's observation time, not the candidate start. Waiting bus consumption is included. The Mission Definition capacities, exact simulation model and FD propellant snapshot come from the same immutable input attempt and run evidence. Telemetry must be fresh at capture and simulation-bound. Propellant uses the end-of-horizon uncertainty/loss lower bound before subtracting scheduled burns. Generation uses the explicit eclipse bound throughout; aggregate sunlight fraction is never treated as constant power. API metadata names this as `ECLIPSE_GENERATION_LOWER_BOUND_THROUGHOUT`. A rejected forecast means failure to establish resource feasibility under those assumptions, not proof that the physical candidate is impossible. Propellant samples are a lower envelope (`HORIZON_END_LOWER_BOUND_MINUS_ALL_BURNS`), not expected remaining mass; margins from different forecast horizons are not directly comparable.

The Planning owner acquires the same spacecraft advisory lock used by schedule commits, then reads every current schedule head with a future interval. Each candidate forecast extends through the latest remaining committed activity, including commitments after the candidate itself. A later commitment outside the propellant model's validity or the evaluator's seven-day bound prevents a pass. Schedule snapshots and exact existing activity catalog evidence are retained with the assessment. This is a consistent recorded proposal assessment; it neither reserves resources nor remains current after another commit.

Existing activity resource profiles must be stored as `planning-activity-catalog` evidence keyed by activity ID by the future commit workflow. A matching activity definition alone is insufficient to guess its catalog resource values. There is no public endpoint for submitting arbitrary resource profiles. The current IMAGE catalog supplies power, total generated data and propellant. DOWNLINK/MANEUVER require their own explicit resource models; no storage reclamation is invented. Rates of in-flight activities are clipped at the telemetry epoch, and their whole burn is conservatively deducted again because this layer lacks a source-bound burn completion receipt.

Remaining integration: illumination-dependent power qualification, required thermal/wheel models, multi-operation candidate sequences, commit-time re-evaluation and atomic schedule/profile publication. Assessment success alone does not complete any of those gates.

## Operation profile integration

`PlanningOperations` now captures Mission Definition's current versioned role bindings and
operation resource profiles, then reads each bound catalog at its exact version. IMAGING must
match the MissionProfile catalog pin. Every bound role must match the catalog's operation and
an explicit approved simulation resource profile. Captured owner documents and hashes travel
with the input attempt and recorded run. Absent or invalid operation inputs produce the explicit
`APPROVED_OPERATION_INPUTS` missing category; historical image-only records remain readable.

For committed activities, the resource evaluator now reads
`planning-activity-operation-profiles` alongside `planning-activity-catalog`, both keyed by
activity ID. Exact catalog resource values and mission binding must agree with the captured
profile. DOWNLINK uses the explicit megabytes-per-second drain and may simultaneously produce
catalog-specified overhead data. Empty storage does not create credit against future data.
MANEUVER uses the approved catalog's burn and load; no positive burn is inferred merely from
the operation name. Invalid operation evidence suppresses the forecast with
`OPERATION_RESOURCE_EVIDENCE_INVALID`; absent non-image evidence remains an unresolved model.

The future commit workflow must publish these per-activity evidence records atomically with
the schedule while holding the spacecraft consistency lock. This writer and automatic
multi-operation candidate generation are still absent. The updated live verifier seeds explicit synthetic IMAGE/DOWNLINK catalogs and profiles.
The 2026-09-12 verification below covers this integration; earlier evidence covers the previous
image-only deployment.

The affected verification passed 39 tests across nine classes, with zero failures/errors/skips, in `/private/tmp/msc-resource-assessments-final-test.log`. It covers waiting consumption, a later commitment causing storage overflow, exact profile requirements, in-flight clipping, source-bound persisted forecasts, future schedule lookup, and live-claim cancellation fencing. A subsequent Opus review led to explicit unread-context, assumption and integrity fields plus regression tests; follow-up verification passed 28 tests across 8 classes with zero failures/errors/skips in `/private/tmp/msc-resource-review-followup.log`. Subsequent deployment and owner-API evidence are recorded below.

The K8s-only owner-API verifier passed 14 named checks in 82.363 seconds, plus unchanged run/assessment reads after cancellation. It independently recomputed terminal battery, storage and propellant values from captured inputs and checked the reported resource decision and API roles. Run/resource `188b6502-f491-39cf-87d6-955399088be6`, attempt `9988e23d-f352-43da-aef9-60c204063c05`; source `/private/tmp/msc-k8s-resource-workflow.log` and `.local/planning-search-verification.json`.

The first replica equality check found identical values but different `modeledResources` array ordering because `Set.copyOf` iterates differently across JVMs. The result now uses an immutable EnumSet for canonical serialization order. Affected follow-up verification passed 37 tests with zero failures/errors/skips in `/private/tmp/msc-resource-api-order-test.log`. The representation fix was deployed as `msc-planning:be4eb5bf5acd1a2bae7f5e79`. The repeated two-Pod comparison passed all six checks in `/private/tmp/msc-k8s-resource-verification-final.log`, against the same historical attempt/run/assessment. No historical data was rewritten.

On 2026-09-12 all nine services were updated sequentially and all ten Pods were Ready with
zero restarts after rollout. `verify-planning-search.py --with-runs --timeout-seconds 300`
passed 15 named owner-API checks in 245.463 seconds, followed by unchanged historical run/resource
reads after request cancellation. It retained exact operation bindings/catalog versions/resource
profiles and independently recomputed the image proposal's reservoir values. Multiple existing
simulation spacecraft competed for the per-attempt geometry budget; this duration is observed
local behavior, not a production latency guarantee.

Request `42b732a3-f004-485c-999d-f4aaab5b81b3`, attempt
`90e719ee-b995-419f-97ba-75d78bd4098c`, run/resource
`557f3d6f-b68b-36a4-b7b8-f02ed6789bc0`. Logs:
`/private/tmp/msc-operation-planning-live.log` and `/private/tmp/msc-operation-replica-live.log`.
The subsequent two-Pod verifier passed all six checks, including identical attempt, linked run
and resource assessment reads. This live flow still proposes imaging only; the explicit downlink
profile is captured but no downlink activity is automatically scheduled or executed.
