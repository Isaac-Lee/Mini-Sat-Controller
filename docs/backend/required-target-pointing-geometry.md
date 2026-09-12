# Required target-pointing geometry

The Flight Dynamics numerical adapter computes the direction from a propagated spacecraft
position to a fixed WGS84 geodetic target. Cartesian inputs use the existing two-body
propagator; public GP inputs use the existing SGP4/SDP4 propagator with original numeric
precision. Traditional TLE export is not an intermediate representation in this calculation.

Each sample reports the satellite Earth-fixed position, Earth-fixed and EME2000 unit line of
sight, slant range, radial/geocentric off-nadir angle, sub-satellite geodetic point and target
visibility at the requested elevation mask. Topocentric azimuth is measured at the target
from geodetic north toward east. The published result contract also carries the orbit
identity, propagation model, source fingerprint and pinned reference archive digest.

Sampling is half-open over the requested horizon, with a 1–3600 second step, at most 20,000
samples, a seven-day horizon bound and seven-day distance from the source epoch. These are
workload/model bounds. They do not establish geometry between samples. In particular,
small off-nadir alone does not establish visibility: an antipodal target can have near-zero
off-nadir while Earth blocks the line of sight.

A required line of sight does not establish actual spacecraft orientation, sensor footprint,
AOI coverage or a feasible attitude sequence. A versioned simulation pointing/footprint model
and its evaluated constraints are still needed for the accepted operational simulation flow.

## Verification checkpoint

The contracts and numerical adapter passed 13 test executions (seven contract
tests and six Orekit integration tests), with no failures, errors or skipped cases at
16:52:15 KST on 2026-09-12. Tests cover Cartesian geometry, GP geometry, independent
Earth-fixed/inertial vector comparison, range, antipodal visibility, cardinal north/east azimuth, positive elevation and bounded sampling.
The FD service and its dependencies also compiled successfully.

The first GP reference test failed because it serialized the reference through rounded TLE
text. The corrected reference constructs its numeric Orekit TLE directly from the fixed GP
fixture and retains the original tight tolerances. Production propagation was unchanged.

Local evidence: `/private/tmp/msc-pointing-geometry-tests.log` and
`.local/pointing-geometry-verification.json`. This checkpoint does not establish the API's
database, authorization, rollback, concurrent-request or deployed HTTP behavior. Those checks
remain pending; the deployment verifier is `scripts/verify-required-target-pointing.py`.
