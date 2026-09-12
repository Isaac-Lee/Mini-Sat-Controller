# Planning consistency and reservoir validation checkpoint

Implemented: pure-domain schedule reconstruction/withdrawal, piecewise resource
validation and a PostgreSQL ScheduleRepository adapter. These are verified building
blocks for the planning service; they are not an automatic planner or a command
release implementation. The four existing service processes remain unchanged.

## Whole-timeline resource model

`ResourceTimeline` consumes an initial estimate anchored exactly at the horizon
start, explicit capacity/reserve limits, a complete nonoverlapping sequence of
power-generation/base-load forecasts, and all activity resource profiles.

Within each interval bounded by activity/forecast changes:

- Battery change is `(generation − base draw − activity draw) × seconds / 3600`,
  in Wh. Charging saturates at the configured capacity; excess energy cannot be
  carried as fictional credit into a later eclipse.
- Storage changes by concurrent generation minus downlink, in MB/s. Empty storage
  clips at zero, so a downlink before acquisition does not create negative capacity
  credit for a future image.
- Propellant requirements are conservatively charged at activity start, in kg.
  Consecutive burns share the same reservoir and reserve limit.

The trajectory includes interval boundaries and internal battery/storage saturation
breakpoints. Half-open activity windows remove ending rates before starting new
rates. Validation checks initial and boundary limits; linear segments have no
unrepresented interior extrema. Output remains a forecast, not telemetry truth.

The schedule-bound entry point requires exactly one matching load profile for every
activity ID/window. Missing profiles, incomplete power forecast coverage or a
required unsupported resource model return `NOT_EVALUATED`, never `VALIDATED`.
The implemented models are battery, storage and propellant. Required thermal or
wheel-momentum models remain unavailable and therefore block validation. The
calling planner must derive the required set and numeric profiles from approved
mission definitions, not let a request caller weaken them.

Work is bounded to seven days and 10,000 loads/forecast segments per invocation.
The model is deterministic and does not import framework, persistence or clock APIs.
Initial estimate freshness, uncertainty margins, real mission model qualification,
external booking confirmation and exact provenance collection remain orchestration
requirements; this numerical result alone does not authorize transmission.

## Immutable schedule snapshots

`MissionSchedule.Snapshot` is a record suitable for persistence without Jackson in
the domain. `restore` rechecks version validity, horizon bounds, unique activity /
assignment / candidate identities, one-to-one assignment links and exclusive
resource conflicts. It does not silently repair corrupt snapshots.

`withdrawFuture(requestId, cutoff)` creates a new version while retaining activity
history before the cutoff. It never unfreezes history or rewrites retained records;
repeated withdrawal with the same boundary and no remaining work is a no-op.
Changing the version invalidates the old resource-validation binding.

Both repositories use the domain successor guard. A valid next version can append
one proposal, withdraw unfrozen future activities, or advance the frozen boundary.
Retained activity/assignment records are immutable, and removed identities cannot
be swapped into another activity in the same transition. Repository history remains
append-only across these transitions.

## PostgreSQL publication

`JdbcScheduleRepository` is explicitly instantiated by its owning planning runtime;
it is not auto-registered into every service. It uses that service's `StateStore`,
canonical schedule-key hash and PostgreSQL transaction-scoped advisory lock.
Head and domain versions must match. Every write checks the expected version and
domain successor invariants before appending history and advancing the head.

The repository joins the caller's transaction, allowing schedule publication and
its outbox fact to commit or roll back together. Exact historical version reads
query the immutable history directly, without loading all prior versions.

The consistency key is spacecraft plus horizon. Selecting nonoverlapping operational
horizons across the spacecraft, cross-service release fencing, and orchestration of
request cancellation versus already transmitted commands are still planning/control
service work. This adapter does not pretend to solve those distributed workflows.

## Evidence

At the original consistency checkpoint, `./mvnw verify` passed 61 tests, without failures or skips. Those tests cover:

- cumulative depletion even when individual tasks are feasible;
- eclipse/base-load consumption, charging saturation and empty-downlink behavior;
- cumulative propellant use, missing/unsupported model rejection, profile binding,
  and explicit saturation breakpoints;
- snapshot round trips, corrupt graphs/overlaps/versions, future withdrawal and
  preservation of prior snapshots;
- independent PostgreSQL connections competing to publish one expected version,
  exact history after withdrawal, rejection of in-place rewrites, and rollback of
  schedule plus outbox in one transaction.

The existing five explicit Orekit reference/access integration tests are a separate
numerical checkpoint. No new planning-service HTTP or complete operating-flow
claim is made by these library/repository tests.

## Evaluation completion boundary

`FeasibilityEvaluation` distinguishes `FEASIBLE`, `INFEASIBLE` and `NOT_EVALUATED`.
Missing sensor/resource calculations remain unevaluated rather than being presented
as a computed rejection. Only FEASIBLE permits `MissionSchedule.commit`; attempting
to commit an unevaluated candidate leaves the original schedule unchanged.
Existing boolean constructors remain available for evaluated callers. The type has
not yet been published as a persistent Planning-service run API; future run records
must retain its explicit status and validation reference.
The full default build on 2026-09-12 passed 103 tests, including the unevaluated
candidate rejection and unchanged-schedule assertion.

## Candidate and run artifacts

`PlanCandidate.snapshot()` and `PlanningRun.snapshot()` expose explicit serializable
value records, avoiding accidental empty JSON objects from domain classes with
non-bean accessors. A candidate retains its original phase/mode and exact activity
definition/version/resources. Restoring it requires the owner to resolve that
immutable approved definition; a different version, removed resource or disallowed
phase/mode is rejected. Run restoration also rechecks candidate/request/run and
decision-reference invariants and preserves all ten pinned input references.

These artifacts do not confer current release authority or perform the missing
search/resource computation. The future service run record must additionally bind
the Tasking request revision and its original input-attempt ID. The snapshot values
are suitable for storage; no new run HTTP endpoint or database publication workflow
is claimed by these domain and JSON tests.
The full default run passed 105 tests on 2026-09-12; the subsequently added JSON
round-trip test passed separately. No service restart was needed for this unused
artifact API, and no live planning-run storage claim is made.

## Service run recording follow-up

The earlier artifact-only boundary above is now extended by [Planning run recording](planning-runs.md).
The service publishes snapshots with explicit request revision and input-attempt binding
inside its live-lease transaction, with protected read APIs and an immutable run index.
Full candidate evaluation, whole-schedule resource orchestration and commitment remain pending.

## Cross-horizon schedule lookup and resource ownership

`ScheduleRepository.overlapping(spacecraft, horizon)` returns current schedule heads
whose physical horizons overlap the supplied interval. The PostgreSQL query uses
TAI seconds/nanoseconds with strict half-open comparisons, filters the owning
spacecraft, and returns all matching heads rather than the generic state-list cap.
Historical versions remain available through the existing exact-version API.
Planning migration V3 adds a partial spacecraft lookup index to its own state table.

The JDBC adapter now acquires a transaction-scoped spacecraft advisory lock before
the schedule-key lock. Under that lock it checks other overlapping schedule heads
for exclusive-resource conflicts before appending the successor. The in-memory
reference adapter applies the same domain compatibility check. Two independent
writers using different horizon keys can no longer both allocate an overlapping
payload interval merely because their per-key CAS values are both zero.

Future resource orchestration must hold `lockSpacecraft` while reading the combined
schedule context, computing the reservoir forecast and publishing its successor.
A snapshot read alone does not reserve resources or prove numerical feasibility.
Current service runs are still unevaluated, and no new commit API is exposed by
this storage change. Newly introduced activities are also checked against every overlapping head’s
frozen physical interval. Retained activities are preserved when another head’s
frozen boundary later advances over their time. Whole-schedule resource/profile
integration remains a follow-up requirement.

The PostgreSQL race and exact boundary/history tests passed in the first reactor
run. That run later stopped during Monitoring’s Ryuk setup (localhost:55432
collided with the local PostgreSQL endpoint), before running Monitoring tests.
A follow-up run with fresh containers passed, including the cross-horizon freeze
regression and the remaining Monitoring, Anomaly and Planning suites. Logs:
`/private/tmp/msc-cross-horizon-verify.log` and
`/private/tmp/msc-cross-horizon-followup-retry.log`.
The adapter/index changes have not yet been exercised by a deployed schedule
commit workflow; no live whole-resource commitment claim follows from these tests.
