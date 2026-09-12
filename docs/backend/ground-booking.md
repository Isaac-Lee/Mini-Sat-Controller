# Durable ground-station reservations

Ground Operations (8106) and station Simulator (8114) now run as independent Spring
Boot applications with service-owned PostgreSQL databases. This implements station
reservation and reconciliation; pass sessions, spacecraft radio links and payload
reception remain pending.

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
