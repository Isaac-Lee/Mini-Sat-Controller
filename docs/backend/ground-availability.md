# Local ground allocation snapshots

Ground Operations now creates immutable availability snapshots for a bounded
planning horizon (at most 24 hours). Each includes the latest registered definition
of each station, all active overlapping local allocation IDs/windows and the
remaining half-open free intervals. Station definitions and allocations are read
by one SQL statement, so they share one PostgreSQL MVCC snapshot. A result over
10,000 joined rows is rejected instead of silently truncated.

The scope is explicitly `LOCAL_ALLOCATIONS_REQUIRE_PROVIDER_CONFIRMATION`.
Unallocated time is a candidate for a reservation; it is not a provider guarantee,
a geometric contact, an RF capability qualification or a successful downlink.
External provider holds or outages outside MSC's local allocation knowledge can
still cause the existing reservation workflow to reject or remain UNKNOWN.

Every active hold blocks time, including TENTATIVE, REQUESTING, UNKNOWN and
CANCEL_PENDING. Confirmed cancellation/rejection releases the existing allocation;
requesting cancellation does not. Adjacent holds merge for free-time calculation.
Both arithmetic and PostgreSQL range filtering preserve nanosecond boundaries.

## API

ADMIN/OPERATOR/SERVICE may call the API; internal paths additionally require SERVICE.

- `POST /api/ground-availability` or `/internal/ground-availability`: body
  `{horizon:{start,end}}`, with TAI instants and `Idempotency-Key`. Returns an immutable
  versioned snapshot. Repeating the same key replays the original snapshot, even
  if bookings have subsequently changed; a new key captures a new view.
- `GET /api/ground-availability/{id}` or `/internal/ground-availability/{id}`:
  returns the original snapshot body.

Snapshot capture does not emit an input-change event, avoiding a self-wakeup loop.
Station definition publication and booking requested/confirmed/cancelled/rejected
facts wake Planning. Planning pins the snapshot for the same horizon used by its
weather query, reports absent station definitions or no free local time, and still
requires reservation confirmation later. No provider call occurs inside the
snapshot transaction.

## Verification

The 2026-09-12 full default build passed 100 tests. Two added PostgreSQL tests cover
latest station versions, active versus released holds, clipped/adjacent intervals,
nanosecond arithmetic, empty/fully occupied calendars, immutable snapshots,
idempotency conflict/replay and snapshot captures producing no self-wakeup event.

`scripts/verify-ground-booking.py` passed ten live checks, including snapshots during
actual local reservation/cancellation against the station simulator. The Planning
verifier passed twelve checks, including reading its pinned snapshot back from
Ground Operations and comparing the full body. Local evidence is retained in
`.local/ground-verification.json` and `.local/planning-input-verification.json`.
These services remain a partial implementation of the full backend goal.
