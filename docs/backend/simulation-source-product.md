# Product-owned synthetic source packages

Product is an independent Spring Boot module with its own database and `msc-product`
S3 bucket. It is deployed in local Kubernetes (evidence below). Completion-event intake is now
connected through the transactional inbox and a durable Product-owned work queue.

POST `/api/products/simulation-source-packages` accepts a `manifestId` UUID and an
`Idempotency-Key` from ADMIN, OPERATOR or SERVICE. Product obtains the Acquisition
manifest through its internal API, requires COMPLETE/SIMULATION with no missing sources,
and checks distinct expected source/payload IDs, source versions, size/hash and pinned
plan bindings. Totals must equal the sum of the 1..64 expected sources.

Product downloads each original through Acquisition's SERVICE-only
`/internal/acquisition/simulation-sources/{id}/content` endpoint, bounded to its declared
size (maximum 64 MiB per source). It checks the actual stream length and SHA-256 before
copying bytes to its own content-addressed bucket. Files are processed sequentially;
only one source is staged in temporary storage at a time and removed on exit.
No direct reads of Acquisition's DB or bucket occur.

The JSON package index (`MSC_SIMULATED_SOURCE_PACKAGE_V1`) retains the full Acquisition
manifest and canonical hash plus each source's receipt ID, payload ID, size, content hash
and Product-owned object reference. Original bytes are preserved exactly. Quality metadata
explicitly states whole-source completeness, packet completeness NOT_ASSESSED and sensor
qualification NOT_ESTABLISHED. This is a synthetic source package, not an assertion that
an instrument-specific L0 format or calibration has been qualified.

Object I/O occurs outside DB transactions. Product metadata, history, idempotent result
and `SimulationSourceProductCreated` outbox event commit together. Retries can reuse
content-addressed artifacts after metadata failure; unreferenced objects may remain.
Successful replay reads stored metadata without downloading/uploading again. GET at
`/api/products/simulation-source-packages/{id}` or its `/internal` counterpart returns
owned product metadata to ADMIN, OPERATOR or SERVICE.

`SimulationProductTest` uses PostgreSQL and mocked owner/storage adapters, checking byte
preservation, index evidence/quality, idempotency, incomplete/inconsistent manifest rejection,
corrupt-byte rejection and outbox rollback/retry. Acquisition's content test checks owned
reference selection, byte stream and missing-source rejection. These tests do not prove
real HTTP authorization, MinIO copies, Product deployment or downstream fulfillment.

`SimulationAcquisitionDataComplete` is routed to Product. The inbox checks manifest
identity, event aggregate/version and complete source evidence, then pins its canonical
hash in `simulation_product_work` without object or owner HTTP I/O. Duplicate events
converge on one job; conflicting events roll back inbox acceptance. Other production
inputs remain retained for their pending workflow.

Workers claim one job with `FOR UPDATE SKIP LOCKED` and a 60-minute lease to cover up to
64 sequential bounded transfers. They retry transient errors after 30 seconds and reject
invalid evidence. A stale worker cannot update a replacement lease status; idempotent
immutable product publication prevents duplicate product records. Work status is readable
at `/api/products/simulation-source-packages/{id}/work` and the `/internal` equivalent.
`SimulationProductWorkerTest` exercises real DB inbox deduplication, recovery after an
expired claim, transient retry, owner-hash mismatch and invalid event envelope rollback.
RabbitMQ live delivery was subsequently verified below.

Next integration: product download, synthetic quicklook and quality
workflow. Packet-level reconstruction, actual instrument L0 qualification and evidence-based
request fulfillment remain in the full backend scope.


## Deployed verification — 2026-09-12

Product image `msc-product:7ccb4983be48a3f1217ae3e2` and Acquisition image
`msc-acquisition:df55f00d9f9cca98ff7e7324` rolled out. Product has its own Secret,
DB account, Service and Deployment; local API port 8112 is managed by the API forward
supervisor. The cluster now contains 12 independent services. Planning and Acquisition
retain two replicas each; Product starts with one.

`verify-simulation-downlink.py --with-manifest` created a fresh real-API synthetic
IMAGE/downlink/reception/manifest flow. Before any explicit Product create call,
`verify-simulation-product.py` observed automatic STORED work and product metadata.
Product `a08bc407-3927-445e-aad0-721dffcf0067` completed in one attempt, preserving
one 1,000,000-byte source. Direct authenticated reads of the Product MinIO bucket
confirmed exact source size/hash and the JSON index's evidence and quality limits.
Manual creation/replay retained the automatic product; requester GET/work access was denied.

The index object is
`s3://msc-product/e039117b697665cbcde4cb888f37932b1bb98cc4565e2acc76a5b01da67da290`.
Evidence: `.local/simulation-product-verification.json`, `/private/tmp/msc-product-live.log`,
`/private/tmp/msc-product-downlink-live.log`. This verifies automatic lossless synthetic
source packaging, not qualified instrument L0, quicklook or request fulfillment.
