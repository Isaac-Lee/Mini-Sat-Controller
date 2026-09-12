# Acquisition simulation source import

Acquisition is an independent Spring Boot service with its own PostgreSQL state and
`msc-acquisition` S3 bucket. This checkpoint implements explicit import of a verified
Simulator downlink receipt. It is not yet deployed in the local Kubernetes cluster.

`POST /api/acquisition/simulation-sources` accepts `{ "receiptId": "<64 lowercase hex>" }`
and an `Idempotency-Key`, for ADMIN, OPERATOR or SERVICE. Acquisition fetches the immutable
receipt and its bytes through Simulator's service-authenticated APIs. It does not read
Simulator's database or bucket directly. Simulator exposes received bytes only when a
stored reception receipt exists, at
`GET /internal/simulation/downlinks/{id}/received-content`.

Before import, Acquisition checks receipt/plan identity, SIMULATION scope, positive
receipt/ledger/link versions, station identity, TAI time, declared size and SHA-256.
HTTP download is bounded to the declared size (at most 64 MiB). Both actual byte count
and SHA-256 must match before upload to the Acquisition bucket. Download and object
upload run outside database transactions. Temporary files are removed on exit.

The source record retains the full owner receipt and its canonical hash, content size
and hash, owned object reference, SIMULATION environment and RAW_SOURCE_STORED status.
Metadata, history, idempotent response and `SimulationAcquisitionSourceStored` outbox
event commit atomically. Retry after database failure can reuse content-addressed bytes;
an unreferenced object may remain after a failed transaction. A successful repeated
import returns its stored record without repeating owner or object I/O.

`GET /api/acquisition/simulation-sources/{id}` and the corresponding `/internal` route
return owned metadata to ADMIN, OPERATOR or SERVICE.

## Verification scope

`SimulationSourceTest` uses real PostgreSQL and mocked owner HTTP/object storage. It
checks exact bytes, replay without I/O, corrupt/truncated rejection, receipt binding,
evidence versions and transaction rollback with same-key retry after outbox failure.
`ServiceHttpDownloadTest` exercises a real local HTTP server, including service
credentials, fixed-length and chunked overflow, non-200 responses and invalid bounds.
These tests do not establish deployed Acquisition or real MinIO import.

## Remaining integration

`SimulatedPayloadReceived` now routes through the transactional inbox into an owned
PostgreSQL work queue. Event receipt hashes are pinned before any owner I/O. Duplicate
events are deduplicated; conflicting evidence rolls back inbox acceptance. Workers claim
with `FOR UPDATE SKIP LOCKED` and a five-minute lease, retry transient failures after
30 seconds, and reject invalid evidence. Completion updates require the live lease token.
An expired worker can at most publish the same immutable source via the idempotent import;
it cannot overwrite a replacement worker status. Other routed inputs are retained for
the still-pending operational flows. Work status is readable at
`GET /api/acquisition/simulation-sources/{id}/work` (also `/internal`).

`SimulationSourceWorkerTest` exercises inbox deduplication, durable retry, expired lease
recovery, conflicting events, owner/event mismatch and retention of other inputs with
real PostgreSQL. RabbitMQ delivery and Kubernetes provisioning/deployed cross-service
import verification remain to be checked. This source is a synthetic raw
byte stream, not qualified instrument imagery, L0, gap repair, quicklook, a product or
Tasking fulfillment. Those stages remain part of the full backend goal.
