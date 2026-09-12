# Simulation source completeness manifests

This source checkpoint adds expected whole-source accounting to Acquisition. It is
deployed in local Kubernetes (evidence below). The existing Simulator transfers a complete synthetic file per downlink;
there is no packet sequence or partial-file transfer model at this checkpoint.

`POST /api/acquisition/simulation-manifests` accepts `scenarioId` and 1..64 distinct
`planIds`, with an `Idempotency-Key`. ADMIN, OPERATOR and SERVICE may call it.
Acquisition retrieves each plan through Simulator's internal API before its transaction,
checks its scenario, unique payload identity, SIMULATION/ONBOARD scope, size and hash,
and pins the full plan envelope. It retains the plan body hash used by reception receipts.
The expected list cannot be empty and cannot count one payload more than once.

The resulting manifest records expected bytes, received bytes, missing plan IDs, pinned
expected sources and the immutable Acquisition-owned source states actually received.
A missing source remains INCOMPLETE. Presence alone is insufficient: each source must
match the pinned plan hash, exact expected content hash/length, SIMULATION environment
and RAW_SOURCE_STORED status. A mismatch raises a conflict and never closes the gap.

`POST /api/acquisition/simulation-manifests/{id}/refresh` reconciles owned source states
under a manifest lock with an idempotent request. Unchanged refreshes create no history
revision. A transition to COMPLETE (or creation already complete) atomically publishes
one `SimulationAcquisitionDataComplete` event with the saved manifest; replay and later
refreshes do not republish it. GET at `/api` or `/internal` returns immutable-versioned
manifest metadata. These routes require ADMIN, OPERATOR or SERVICE.

`SimulationManifestTest` uses PostgreSQL and mocked owner HTTP to verify incomplete to
complete transition, pinned source mismatch, exact-once completion event, replay without
owner I/O, creation already complete, outbox rollback/retry, and invalid expectations.
Inherited source tests also run. This does not prove deployed HTTP authorization or
RabbitMQ delivery of manifest completion.

Source arrival now reconciles registered manifests automatically in the same database
transaction as source metadata/history and its outbox event. The membership table is
created/backfilled by migration V3. Registration locks expected source IDs in sorted
order before accounting and linking; import holds the same source lock. Thus registration
and source arrival cannot miss one another. A source update locks linked manifests in
sorted order. No owner HTTP or S3 I/O is added inside these transactions.

Tests additionally cover automatic completion on import, atomic rollback of both source
and manifest when the completion event fails, and concurrent registration/import without
lost completion. Independent Product consumption remains to be connected. No manifest here asserts packet-level completeness, physical sensor
qualification, a request/assignment binding, L0 generation, quicklook or fulfillment.
Those remain required by the full backend goal.


## Deployed verification — 2026-09-12

Acquisition image `msc-acquisition:034a541bfa59fcec966bae25` rolled out with two
replicas retained. `python3 scripts/verify-simulation-downlink.py --with-manifest`
created a manifest before reception through the real API, then executed the synthetic
IMAGE, Orekit-backed station booking and DOWNLINK flow. RabbitMQ source intake
completed the manifest automatically, without calling refresh to cause the transition.

Manifest `cb5636c8-67c4-4306-8ebd-10980954ae8c` changed from INCOMPLETE version 1
to COMPLETE version 2 for receipt
`4fdc53ab85dba452a24546b0c9b0a2e58834efc4d22de7e20261f5f87cd8f5f4`.
Its expected/received totals were exactly 1,000,000 bytes. Expectations were preserved,
received source metadata matched, repeated refresh retained version 2, and requester
GET/refresh calls returned 403. The companion `verify-acquisition-source.py` also
passed its real MinIO byte-count/hash checks for this receipt.

Evidence: `.local/acquisition-manifest-verification.json`,
`/private/tmp/msc-manifest-live.log`, `/private/tmp/msc-manifest-source-live.log`.
This proves automatic whole-source accounting with two Acquisition replicas, not packet
completeness, L0/product generation or full request fulfillment.
