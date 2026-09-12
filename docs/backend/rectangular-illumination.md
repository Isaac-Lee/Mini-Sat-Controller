# Rectangular illumination numerical search

Flight Dynamics now provides `POST /api/rectangular-illumination` and the corresponding
`/internal` path, using the same request shape as target illumination. The result is stored
under its own identity and read with `GET /api/rectangular-illumination/{id}`. Operator/service
roles can compute; existing global FD read authorization applies to reads. Request identity,
idempotency replay, result history and outbox storage use the existing owned persistence path.
The earlier five-point endpoint and its stored records retain their original semantics.

The new computation searches the continuous spatial solar-elevation lower bound over the
nonwrapping geodetic rectangle, including the enclosing-Earth parallax correction, using the
pinned Orekit Earth frame and analytical Sun provider. A clock-only propagator drives time;
its synthetic position and attitude do not enter the illumination function. The switching
function is threshold minus spatial lower elevation, with negative values indicating light.
Orekit's [FunctionalDetector](https://www.orekit.org/site-orekit-latest/apidocs/org/orekit/propagation/events/FunctionalDetector.html)
supports this date-dependent function; the locally pinned 13.1.8 binary was checked for the
same `withFunction` interface.

The result explicitly carries `SPATIAL_BOUND_NUMERICAL_EVENT_SEARCH`, reference digest, original
query, solar model accuracy note, 1 ms root tolerance and 60 s maximum check interval. Search
is limited to 24 hours and the reference archive's coverage. Numerical event searching can
miss excursions between checks and has floating-point/root-location uncertainty. It is not
a certificate of temporal completeness, an ephemeris accuracy bound, or a physical terrain
model. Planning's ILLUMINATION gate must not become VALIDATED solely from this result.

Verification includes a real pinned-reference day/night search compared to an independent
topocentric grid at more than 1,000 location/time combinations inside returned windows,
wide-area near-zenith rejection, invalid threshold and oversized-horizon rejection. The
existing spatial-bound and illumination regressions are included. A direct-controller
PostgreSQL integration test covers persisted query/digest/scope, replay without recomputation,
one outbox event, and conflicting retry rejection. This DB test does not enforce Spring
method security; deployed HTTP validation remains a separate step.

The focused run passed 37 tests across six classes with zero failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-rectangular-illumination.log`). This change has
not yet been rolled out to the running Flight Dynamics deployment.
