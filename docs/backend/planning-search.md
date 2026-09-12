# Automatic point geometry search

Planning now invokes Flight Dynamics from input collection when a mission has a
pinned orbit designation, approved activity catalog, agility model and Orekit
reference digest. Cartesian and public GP sources use their respective owner APIs.
The result remains `POINT_GEOMETRY_ONLY` with `NOT_EVALUATED` feasibility. Visibility
of the AOI centre does not establish sensor coverage, illumination, resource
feasibility, ground reservation, command authority or a committed schedule.

Each immutable input attempt retains its request ID and revision, source evidence,
approved catalog independently of Anomaly availability, and the returned prediction
ID/body. Operators and services retrieve the attempt through the existing
`/api/planning/input-attempts/{id}` and internal equivalent. The prediction itself
is also retrievable from Flight Dynamics. `pointGeometry.value.searchKey` identifies
the durable Planning cache entry.

Searches start at the preceding five-minute TAI boundary and end at the earlier of
that boundary plus 24 hours and the request deadline. This separate search horizon
can contain a short past interval and can end before the moving input-collection
horizon. A future candidate-selection stage must intersect access windows with its
actual future planning horizon; it must not schedule past windows. The current
stage makes no schedule assignments. Bucket rollover renews the search naturally;
there is no lifetime request budget that permanently disables replanning.

Cache keys bind the query, spacecraft, orbit evidence hash, agility version and
hash, exact catalog version and hash, AOI and reference digest. Reuse precedes the
Flight Dynamics call. Returned spacecraft, solution, query and reference digest
must match; windows must be ordered, nonoverlapping, within the search horizon and
long enough for the approved activity. The Flight Dynamics idempotency key also
binds the source context, so a changed reference cannot silently replay a prediction
under a different reference set.

The existing request revision, cancellation and live-lease fence guards both cache
publication and the immutable input attempt in one local transaction. Cache writes
lock in spacecraft order to avoid reversed lock order across concurrent requests.
A cancelled/expired claim may have caused a remote computation, but cannot publish
it as a valid local planning attempt. No network call runs inside this transaction.

Input collection rotates the starting spacecraft by the durable attempt number.
Each attempt captures at most 32 missions and makes at most two fresh geometry
HTTP attempts in a separate 20-second numerical-search phase after bounded input collection. Durable cache hits do not consume
this budget; failed HTTP attempts do consume it, so upstream failures cannot cause
unbounded retries within one collection attempt. The Mission Definition list still has
its existing 500-record cap and lacks pagination; support beyond that cap is not
claimed. There is no global cross-request computation rate limiter yet. Search
reuse and bounded attempts reduce repetition but do not prove production capacity.

## Verification

`PlanningGeometryTest` exercises bucket reuse/renewal and wrong-spacecraft rejection
on the public GP endpoint. Existing `PlanningIntakeTest` covers request and lease
fencing. `scripts/verify-planning-search.py` performs the live synthetic orbit search
and checks persisted owner evidence; `--model-only` exercises the separately
published simulation planning model without creating a planning request. The
latest model-only live run passed five checks. The full search live verifier passed eight checks, including a known future orbital
subpoint window, immutable prediction binding and unevaluated full feasibility.
The full reactor passed 114 tests; a later six-test intake run additionally verifies
that cancellation blocks both cache and attempt publication.

## Approved activity options

Each asset now independently pins its versioned `simulationModel` alongside the
catalog and geometry. A missing or mismatched model remains an explicit missing
input. Search uses the smaller of the agility and sensor off-nadir limits, with
the captured model hash in the local cache key. It still does not establish AOI
coverage or sunlight suitability.

`activityOptions` intersects each geometric window with the remaining future
request horizon and assigns the exact approved catalog duration. The catalog must
permit the model's phase and mode; incompatible phases/modes produce no options.
Past windows and windows too short before the deadline produce no options. These
are intermediate activity-sized options, not `PlanCandidate`, `PlanningRun`, or
committed schedules. Every option has `NOT_EVALUATED` feasibility and explicit
remaining AOI/sensor, illumination, attitude, resource, booking, current mode/safety
and schedule-conflict gates. The simulation model's mode is a planning assumption;
it does not substitute for fresh telemetry at validation or release.

The affected reactor passed 73 tests after this integration, including tests for
past-window clipping, approved duration, deadline overflow, forbidden phase and
a narrower sensor angle in the Flight Dynamics request.

The expanded live verifier passed nine checks, including pinned model version and
activity options; a previously stored input attempt also remained readable with
empty newly introduced model/option fields.

The cache-budget/event follow-up passed 75 affected-reactor tests. Planning now
subscribes to `SimulationPlanningModelPublished`, allowing the existing input
epoch mechanism to wake waiting requests when the approved simulation model
changes. A real broker-delivery regression covers this routing.

The next service stage now records [source-pinned domain runs and candidates](planning-runs.md)
when every required input category is present. Missing inputs remain in the attempt;
candidates remain unevaluated until the remaining numerical/operational gates are implemented.


## Readiness ordering (2026-09-12)

A deployed automatic-camera verification exposed a starvation case: several old synthetic
spacecraft repeatedly consumed the two numerical calls before the new, fully configured
spacecraft was evaluated. The first run timed out without creating a candidate; it was not
a successful camera verification.

Planning now collects bounded source inputs before allocating numerical calls. Assets with all
required source categories, catalog, simulation model and operation bindings are considered
first; fewer missing conditions break readiness ties. Stable sorting preserves the existing
rotated fleet order for equal readiness. No evidence is dropped and missing inputs still remain
missing. Numerical search has its own 20-second phase budget and retains its two-call cap.
This improves selection readiness; it does not claim an unbounded-fleet throughput guarantee.
