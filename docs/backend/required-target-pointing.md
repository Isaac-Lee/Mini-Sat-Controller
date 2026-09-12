# Required target-pointing API

Flight Dynamics resolves an owned orbit by ID, computes a sampled required line-of-sight
profile and stores an immutable result. The [geometry contract](required-target-pointing-geometry.md)
defines the frames, angle conventions and sampling limits. This API does not establish
actual spacecraft attitude, sensor coverage or overall planning feasibility.

## Requests and ownership

`POST /api/required-target-pointing` accepts an `Idempotency-Key` and a body containing
`solutionId` and `query`. The query contains a fixed geodetic `target`, TAI `horizon`,
`stepSeconds` and `minimumElevationDegrees`. OPERATOR and SERVICE roles can create results.
The corresponding `/internal/required-target-pointing` route requires SERVICE credentials.

`GET /api/required-target-pointing/{id}` returns the persisted result to authenticated callers.
The corresponding `/internal/required-target-pointing/{id}` route requires SERVICE credentials.

The service looks up both Cartesian `orbit` and GP `public-orbit` records. Missing input is
404; ambiguous ownership is 409. The Cartesian body solution ID must match its stored key.
The GP snapshot ID must bind its NORAD number and raw-source hash. Requests cannot supply
replacement orbit vectors or elements.

Results identify the spacecraft and orbit propagation model, retain the complete query and
carry the pinned UTC/EOP archive digest. The GP source hash is the preserved external raw
content hash. A Cartesian source hash is the canonical fingerprint of the validated initial
state; it does not represent a raw external source document.

## Persistence and concurrency

Numerical calculation runs outside the database transaction. The result, immutable history,
`RequiredTargetPointingComputed` outbox event and idempotency response commit atomically.
An identical key/request replays the original response; changing the body under the same key
is a conflict. Concurrent duplicates may calculate independently but produce one stored
result and one event. Failed persistence permits retry with the original key.

This query-output event currently has no subscriber route. It does not awaken Planning or
promote a feasibility gate. Later consumers must pin the owned result explicitly when
building a planning assessment.

## Verification

`RequiredTargetPointingApiIT` exercises the real PostgreSQL schema and pinned Orekit archive
through the controller: both orbit kinds, binding rejection, immutable history, replay,
outbox failure rollback and synchronized duplicate requests. These direct controller tests
do not exercise HTTP security.

`scripts/verify-required-target-pointing.py` is the deployed HTTP verifier. It checks the
preserved NORAD 63229 source, replay/conflict/error responses and requester write denial,
and compares returned Earth-fixed geometry with independent WGS84 vector arithmetic.
Execution results are recorded below only after these checks run.

The PostgreSQL/Orekit API test run passed **10 executions**, with zero failures, errors or
skips at 16:56:54 KST on 2026-09-12. The injected outbox constraint failure left no result,
history, event or idempotency record, and retry under the same key succeeded. Evidence:
`/private/tmp/msc-pointing-api-tests.log`. This complements the 13 numerical/contract
executions in the geometry checkpoint.

## Deployed HTTP checkpoint — 2026-09-12

Flight Dynamics image `msc-flight-dynamics:60208680013e420b450d7e8c` rolled out in
local kind. The HTTP verifier passed for preserved NORAD 63229 snapshot
`gp-63229-4ae2cc8392a6d22b9f274f446ceed4a93d1aef38f3d0473ee0b8788c186ceae9`.
Result `4f7f838d-12d9-41bd-a16d-f0abb30bf873` contains ten samples. Independent
WGS84 arithmetic agreed with all returned Earth-fixed LOS, radial off-nadir and
topocentric angles; maximum slant-range difference was about 1.9 nanometres. That
is numerical agreement between calculations, not physical orbit accuracy.

Requester write denial, stored-result replay/readback, same-key/different-body conflict,
missing-source and invalid-step rejection, and original GP preservation also passed.
Evidence: `.local/required-target-pointing-verification.json` and
`/private/tmp/msc-pointing-live.log`. Full inertial rotation is covered by the numerical
tests; this HTTP verifier checks the inertial vector norm. No actual attitude,
AOI coverage, schedule commitment or command release is established by these checks.
