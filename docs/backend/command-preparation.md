# Pinned semantic command preparation

Planning exposes a read-only `POST /api/planning/schedules/query` (also `/internal`) with
`{key, version}`. Only an exact positive committed version is returned through the owned
schedule repository; missing history is 404, not an invented empty schedule. It accepts no
caller-supplied snapshot or schedule mutation. API readers are ADMIN/OPERATOR/SERVICE.

Control `POST /api/command-loads/prepare` accepts a load identity, schedule key/version,
correlation version, catalog references and parameter maps keyed by activity ID, and commit
deadline. It retrieves all source content from the Planning and Mission Definition APIs and
checks returned identities and versions. The compiler restores schedule invariants, verifies
mission/correlation bindings and exact activity definition/version/resource sets, validates
parameters against the catalog, and requires every scheduled duration to match the catalog
exactly on the onboard clock's tick grid. Commands are ordered by physical start time then
activity ID, and command IDs derive deterministically from load ID plus activity ID.

The prepared artifact preserves complete source content and its canonical hash. A separate
semantic checksum includes load identity, sources and generated commands; this is not a claim
about encoded RF packet bytes. Prepared artifacts and `CommandLoadPrepared` outbox facts are
stored together. A same-key retry returns the stored artifact without refetching owners.
ADMIN/OPERATOR/SERVICE can prepare and read; requester access is denied.

**Preparation grants no authority.** `authorizationEvidenceReference` remains empty. A resource
status of NOT_EVALUATED is retained rather than promoted to VALIDATED. The release boundary
must still retrieve current schedule/resource/booking/safety/authority evidence and approvals
and invoke `CommandReleasePolicy`; Space Link dispatch is not connected by this feature.
Compiling an historical schedule does not authorize sending its commands now. Limit: at most
100 activities in a prepared load, matching the current simulator's load bound.

Ten focused tests passed (`/private/tmp/msc-command-preparation.log`): three compiler tests,
five Control HTTP/DB/broker tests (including persisted preparation replay with owner APIs
mocked, plus missing-owner 404 without a prepared artifact), and two schedule-query repository-boundary tests. Those two query tests use a mocked
StateStore and are not HTTP/DB integration proof. No complete Planning-commit-to-Control-release
workflow is claimed; the production schedule commit/revalidation path remains unfinished.

The updated Planning and Control images were deployed to the local kind cluster, preserving
Planning's two replicas. `scripts/verify-command-preparation.py` passed six live checks:
requester denial at both APIs, missing schedule rejection, downstream owner absence remaining
404, no prepared artifact after failure, and previously persisted Control execution evidence
remaining readable. This proves deployed rejection and persistence boundaries; it does not
prove successful cross-service preparation from a production-committed schedule.
