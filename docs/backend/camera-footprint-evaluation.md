# Sampled simulation camera footprint evaluation

Flight Dynamics evaluates an owned required-pointing result with an exact Mission Definition
camera version. The camera pins its simulation Planning model version. Results preserve both
model envelopes and canonical hashes, orbit identity/source hash, the reference archive digest,
the AOI query and each sample's outcome. Caller-supplied orbit vectors or camera coefficients
are not accepted.

`POST /api/camera-footprint-evaluations` (OPERATOR or SERVICE, `Idempotency-Key`) accepts:

```json
{
  "query": {
    "pointingResultId": "owned-pointing-result-id",
    "cameraModelVersion": 1,
    "area": {
      "id": "requested-area",
      "west": 127.37,
      "south": 36.34,
      "east": 127.39,
      "north": 36.36,
      "sourceReference": "request-revision"
    }
  }
}
```

The response is a state envelope containing a small manifest. `GET /api/camera-footprint-evaluations/{id}`
returns the manifest; its `/result` route streams the full JSON artifact from Flight Dynamics-owned
object storage. Equivalent `/internal` routes require SERVICE through the shared security policy.
Public reads require authentication. Reusing a key returns the original result; changing its request
returns 409. Subsequent model publications do not change prior results.

The implementation revalidates the orbit identity/hash/propagation model, pointing model and fixed
target, exact camera/Planning envelope versions, spacecraft identity, mission-definition equality
and the camera's off-nadir bound against its pinned Planning model.

The AOI mapping is `WGS84_LOCAL_LINEAR_RECTANGLE_V1`. The pointing target must match its
latitude/longitude centre within 1e-9 degrees. At target latitude phi and height h, WGS84 prime
vertical radius N and meridional radius M give width `(N+h) cos(phi) deltaLongitude` and height
`(M+h) deltaLatitude`, with angular extents in radians. Supported absolute latitude is at most
80 degrees and each mapped dimension is positive and at most 50 km. The existing Area type
excludes antimeridian-crossing rectangles.

This defines a local linear planar rectangle approximation, not an exact geographic projection
or corner bounding box. Its error relative to geographic coverage has no established sign.
Every result carries this limitation and the applied dimensions and domain limits.

At each owned sample, [the stare-camera projection](stare-camera-projection.md) yields four
footprint corners, area, planar coverage fraction and a conservative pixel-axis-spacing bound.
The bound is compared with the camera's requested GSD ceiling; it is not measured GSD.
Off-nadir and target-elevation comparisons remain available even when projection is impossible.
A projection failure records `UNEVALUATED` with a reason for that sample. Binding or AOI-domain
violations reject the request.

The endpoint accepts at most 2,000 samples and a serialized result of at most 16 MiB; it never
truncates evidence. The completed artifact is stored before the database transaction, which
atomically saves metadata, immutable history, idempotency and the outbox event. A failed DB
transaction may leave an unreferenced object, but cannot publish partial metadata or an event.

The only result scope is `SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE`. Planning must still bind
the full AOI and source versions to its own run and establish exposure-duration and attitude
evidence before approving coverage. This endpoint does not authorize a schedule or fulfill a request.

On 2026-09-12 at 17:49 KST, 16 PostgreSQL API tests and six numerical projection tests passed
with no failures, errors or skips. They cover full/partial/tiny planar coverage, oblique projection,
unevaluated geometry, AOI limits, owner mismatches, sample limits, retained model envelopes,
replay/history and actual post-write outbox failure with rollback and same-key retry. DB tests use
mocked Mission Definition HTTP and object storage; the deployed verifier
`scripts/verify-camera-footprint-evaluation.py` separately exercises those real boundaries.


The deployed HTTP/MinIO check also passed on 2026-09-12. Synthetic spacecraft
`000-sim-search-11ebb35c3b674c5186abea64ceb766b6`, camera version 2, produced evaluation
`489f7f4e-c76c-4d0e-a525-5f6938601fda` with 12 computed samples. Independent ray/plane
corners agreed within 1.13e-10 m (numerical agreement, not physical accuracy). The 12,113-byte
MinIO artifact matched the streamed JSON, with SHA-256
`e1196fcbdeea540871bd4d9ab036c541628794cc32f86510d7189ea98f1a8a86`.
Role rejection, idempotent replay, changed-body conflict, off-centre AOI rejection and preservation
of the original Planning run passed. The deployed image is
`msc-flight-dynamics:e3f936ed42c93926d19b2b05`. This does not extend the earlier full-reactor
checkpoint to all subsequent changes; the checks above are focused on this feature.
