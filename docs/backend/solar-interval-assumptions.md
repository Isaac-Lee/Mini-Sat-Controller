# Owner-scoped solar interval assumptions

Mission Definition exposes ADMIN-only `POST /api/solar-interval-assumptions` with
`expectedVersion` and an `assumptions` record. The record identifies spacecraft, mission
definition, SIMULATION environment, solar model, UTC/EOP reference digest, valid physical
interval, rectangular geographic extent at one exact altitude, maximum temporal rate in
radians/second, evaluation error in radians, maximum step in seconds and justification.

The declaration covers the complete spatial lower-bound function for **every subrectangle**
inside that extent, including the parallax term and numerical/frame/time evaluation error.
It is not a claim that a rate established only for one selected rectangle automatically
applies to others. Zero error, nonfinite/negative rate, invalid reference digest, unsupported
step and HARDWARE environment are rejected. There are no numeric defaults.

The shared contract requires exact solar model and digest, full time containment, geographic
containment and equal altitude before reuse. An AOI label alone does not establish coverage.
The time interval and extent use the existing TAI/nonwrapping geodetic contracts.

Publication binds the mission definition and uses the established per-spacecraft lock,
expected-version comparison, immutable history, idempotency and outbox transaction. API/internal
reads provide current or exact historical revisions to ADMIN/OPERATOR/SERVICE. A replay of an
older publication keeps its original revision after updates. The API does not install default
assumptions for NORAD 63229 or treat admin publication as a physical error qualification.

Contract tests cover wrong model/digest/time/area/altitude and unsupported assumptions. The
HTTP/DB test covers role restrictions, revisions, replay after update, history/current reads,
stale publication and mission mismatch. Flight Dynamics must still fetch an exact owner version,
check coverage and preserve it in the interval result before Planning can consume this data.
The owner and FD calculation APIs are now deployed and verified together; see
`solar-interval-api.md` and `scripts/verify-solar-intervals.py`. This does not complete schedule
feasibility or establish physical validity of the declared assumptions.

The focused run passed four tests across the contract and two HTTP/DB suites, including the
existing authority-policy regression, with zero failures/errors/skips (`2026-09-12`, local
log `/private/tmp/msc-solar-assumptions.log`).
