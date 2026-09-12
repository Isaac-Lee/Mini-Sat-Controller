# Simulation camera model

Mission Definition owns an ADMIN-published, versioned camera model for each spacecraft.
It declares a synthetic orientation law, focal-plane geometry, pixel sampling and acceptance
bounds. Publishing a model does not establish coverage or planning feasibility.
The numerical [staring-camera projection](stare-camera-projection.md) is implemented separately;
its owner API and Planning assessment integration remain unfinished.

## Declared law

`STARE_TARGET_TANGENT_PLANE_V1` points the boresight at a fixed WGS84 geodetic target.
The along-axis is geodetic north projected perpendicular to the boresight; across is
`along × boresight`, giving `across × along = boresight`. A boresight less than one degree
from either direction of the north axis is rejected by the evaluator.

The rectilinear raster has uniform spacing on the sensor plane, not uniform angular or
ground spacing. Pixel rays intersect the target geodetic tangent plane. The law supplies
an explicit synthetic orientation; it is not a claim about actual spacecraft attitude.

Required numeric fields are:

| Field | Units and publication bound |
| --- | --- |
| `halfAngleAcrossDegrees`, `halfAngleAlongDegrees` | Half angles, >0 through 45 degrees |
| `rasterColumns`, `rasterRows` | Integers, 1 through 50,000 |
| `maximumGroundSampleDistanceMeters` | Acceptance ceiling, >0 through 10,000 metres |
| `maximumOffNadirDegrees` | >0 through 60 degrees, no wider than the pinned Planning model |
| `minimumTargetElevationDegrees` | 0 inclusive through 90 exclusive, degrees above local horizon |

These are declared simulation/workload bounds, not SPACEEYE-T1 instrument specifications.
The model also requires `spacecraftId`, `missionDefinitionVersion`, `environment=SIMULATION`,
`simulationPlanningModelVersion`, `approvalReference` and `provenance`. It does not inherit
swath width or nominal GSD from the Planning model. Actual projection can reject a published
camera/geometry combination when its rays cross the tangent-plane horizon.

## Publication and history

`POST /api/simulation-camera-models` requires ADMIN, an `Idempotency-Key`, and
`{expectedVersion, model}`. The service resolves the owned mission profile and current
simulation Planning model. Both must identify the same spacecraft and mission definition;
the requested Planning version must equal the resolved version. Camera revisions use CAS.

The camera record, immutable history, outbox event and idempotency response commit in one
transaction. An identical request/key replays its stored response; changing the body under
the same key conflicts. Later Planning revisions do not rewrite the camera's pinned version.
Consumers must compare that pin with their own immutable assessment inputs.

`GET /api/simulation-camera-models/{id}` and `/{id}/versions/{version}` return current and
exact historical envelopes to ADMIN, OPERATOR and SERVICE. Equivalent `/internal/` routes
require SERVICE credentials. This API does not grant command authority or evaluate
`AOI_SENSOR_COVERAGE`.

## Verification

Ten real HTTP/PostgreSQL/RabbitMQ tests passed with no failures, errors or skipped cases
at 17:14:10 KST on 2026-09-12. They cover publication, immutable historical readback,
CAS, idempotency, role enforcement, parameter bounds and owner/version binding. A database
constraint rejects the outbox insertion after state/history writes; the API rolls everything
back and accepts the identical retry after the fault is removed.

Evidence: `/private/tmp/msc-camera-model-tests.log`. Test fixtures are explicitly synthetic.
The deployed verifier is `scripts/verify-simulation-camera-model.py`; deployed results are
recorded only after that script succeeds.

## Deployed checkpoint — 2026-09-12

Mission Definition image `msc-mission-definition:4418b703dc4400cd587a90e3` rolled out in
local kind. The deployed verifier published camera versions 1 and 2 for synthetic spacecraft
`000-sim-search-11ebb35c3b674c5186abea64ceb766b6`, pinned to simulation Planning model v2.
ADMIN-only publication, idempotency/CAS, historical v1 preservation, service reads, invalid
version pin and non-SIMULATION rejection passed. The existing Planning source and run were
unchanged. Evidence: `.local/simulation-camera-model-verification.json` and
`/private/tmp/msc-camera-model-live.log`. These checks prove model publication, not coverage
evaluation or command authority.
