# Human command approvals

Control accepts `POST /api/command-loads/{id}/approvals` with `checksum`, `validUntil`
(TAI), and `expectedVersion` (zero for an actor's first approval). Only authenticated
OPERATOR principals can grant; the actor identity and HUMAN kind are set by the service,
never taken from a request body. Service/requester principals cannot impersonate human
approval. ADMIN without the OPERATOR role does not grant human approval either.

The checksum must match the immutable prepared load. Approval validity starts at the
service's injected mission clock and must end after now, no later than the load's commit
deadline. The complete domain binding includes load identity, schedule key/version,
checksum, mission definition version and time correlation identity.

`POST /api/command-loads/{id}/approvals/revoke` accepts `expectedVersion` and only revokes
the authenticated actor's approval. An operator can renew a revoked/expired approval with
the current expected version. Another operator's approval is preserved. Both operations
serialize on a per-load database lock and store history, an outbox event, and idempotency
response in one transaction. Retries replay their historical response; consumers must read
current approval state for a release decision. At most 100 distinct operators are retained
per load; renewals do not consume another slot and no list silently truncates a valid ledger.

API/internal `GET /command-loads/{id}/approvals` returns current versioned records to
ADMIN/OPERATOR/SERVICE under the existing API/internal security boundaries. The owner must
still evaluate these records with `CommandReleasePolicy`, including distinct human actor
count, expiration, revocation and exact binding. Recording approval does not set the load's
authorization reference, validate resources/bookings/safety, or transmit anything. The
release transaction must use the same per-load approval lock when it is connected.

Integration coverage uses actual Control PostgreSQL and real HTTP denial for requester and
service accounts. Grant/renew/revoke are additionally exercised through the controller with
a deterministic mission clock and a synthetic prepared load; this is not a production
Planning-to-release workflow or deployed positive HTTP approval proof.

The focused run passed 21 tests across the release policy, command compiler and Control
integration suites with no failures/errors/skips (`2026-09-12`, local log
`/private/tmp/msc-command-approval.log`). The running Control image has not yet been updated
with these approval endpoints.
