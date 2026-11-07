# Durable ground-station reservations

Ground Operations (8106) and station Simulator (8114) now run as independent Spring
Boot applications with service-owned PostgreSQL databases. This implements station
reservation and reconciliation plus SIMULATION pass sessions (below); spacecraft
radio links and payload reception remain pending.

Station catalogs are immutable and versioned in each service. Ground Operations
checks actual Flight Dynamics contact predictions, exact station/spacecraft binding,
window coverage and capacity before persisting a tentative booking. PostgreSQL
numeric half-open ranges exclude overlapping active station allocations, including
nanosecond boundaries beyond floating-point precision. Adjacent intervals are allowed.

An outbox-backed reservation workflow persists REQUESTING before network dispatch.
Stable IDs, provider query-by-ID, bounded leases and fenced completion handle lost
responses and crashes. UNKNOWN preserves the station allocation until reconciliation.
CONFIRMED requires a matching external receipt. Cancellation retains the allocation
until the provider confirms CANCELLED. The simulator stores cancellation tombstones
so delayed create/retry requests cannot resurrect cancelled bookings.

ADMIN registers matching station definitions using `/api/stations` in Ground Operations
and `/api/simulation/stations` in Simulator. Ground `/api/bookings` and `/internal/bookings`
accept station ID/version, spacecraft ID, TAI window, accessPredictionId and requested
megabytes. Poll GET `/api/bookings/{id}` for current state; initial idempotent POST
responses remain historical. POST `/api/bookings/{id}/cancel` requests cancellation.
Ground booking APIs require OPERATOR/ADMIN/SERVICE, with internal routes additionally
requiring SERVICE. Mutating POST requests require `Idempotency-Key`.

```sh
./scripts/run-local-service.sh ground-operations 8106
./scripts/run-local-service.sh simulator 8114
python3 scripts/verify-ground-booking.py
```

Live API verification covers lost reply reconciliation, corrupt receipt rejection,
exclusive/adjacent reservations, concurrent booking conflicts, cancellation tombstones,
and no stale CONFIRMED response after cancellation. Two PostgreSQL tests independently
cover exact-range exclusion and concurrent writes without application advisory locks.
The simulator fault API is ADMIN-only and is not a real ground-station integration.

## SIMULATION pass sessions

`SimulatedGroundStation` implements the `msc-ports` `GroundStationPort` (ADR-0008) over
the durable bookings above and the Simulator station-booking provider. It is not RF and
not a real ground station; every pass record carries `environment: "SIMULATION"`.

- `book(opportunity)` does not create reservations: a `ContactOpportunity` lacks the
  station version, access prediction and volume, so reservations still go through
  `/api/bookings`. `book` resolves the booking whose ID is the opportunity ID, checks
  spacecraft, station and TAI window, and maps CONFIRMED to CONFIRMED and
  TENTATIVE/REQUESTING/UNKNOWN to TENTATIVE. REJECTED and CANCEL* are rejected with 409.
- `begin` needs a CONFIRMED booking, which the Simulator must also hold as CONFIRMED
  with the same receipt. The TAI clock must be inside the half-open window
  `[start, end)`. A begin before the window or after it (a stale booking) is rejected
  with 409. The session ID is `pass-{bookingId}`, so there is at most one session per
  booking, and a repeated begin returns the existing session.
- `report` is allowed only at or after the window end. If the local booking is still
  CONFIRMED and the Simulator still holds it, the outcome is `COMPLETED`, with
  `contactEnd` set to the window end and `accountedBytes` set to the booked
  `requestedMegabytes` × 10^6 (decimal MB, rounded half-up). That is the booked
  volume, not a measured reception. It is credited in full even when `contactStart`
  (the begin time) is late in the window. Otherwise the outcome is `BOOKING_LOST`, with no
  contact end and 0 bytes. This covers a cancellation during the pass and a Simulator
  404. A report for a session that is already REPORTED returns the first report and
  writes nothing. Cancelling a booking whose pass is still ACTIVE after the window
  end returns 409: report the pass first, so the outcome reflects the contact rather
  than when the report was filed. A repeated begin returns the existing session even
  after the booking was cancelled.

There are no SNR, link-budget or other RF figures.

States: `ACTIVE` → `REPORTED` (terminal). Endpoints (OPERATOR/ADMIN/SERVICE; POSTs need
`Idempotency-Key`):

| Method | Path | Body | Notes |
| --- | --- | --- | --- |
| POST | `/api/bookings/{id}/pass` | none | begin; returns the pass revision |
| POST | `/api/passes/{passId}/report` | `{bookingId}` | a `bookingId` that differs from the session's is a stale handle (409) |
| GET | `/api/passes/{passId}` | | current pass record |

If the Simulator is unreachable, the call returns 503. Other Simulator HTTP errors, and
replies that do not match the booking, return 502. In both cases the session stays
ACTIVE, so the report can be retried. `PassApiTest` (Testcontainers PostgreSQL, mocked
Simulator HTTP) covers begin/report idempotency, illegal transitions, stale windows and
handles, cancellation during a pass, and recovery from a provider outage. No live
two-service run covers passes yet.
