# Synthetic L0 reconstruction, quicklook and request fulfillment

Everything here is **SIMULATION / synthetic**. It reconstructs a synthetic L0 record from a
stored source package and checks byte integrity only. It does not measure radiometric or
image quality, georeference anything or establish physical AOI coverage or cloud cover.

## API

POST `/api/products/simulation-source-packages/{productId}/l0` (ADMIN or OPERATOR,
`Idempotency-Key` required) with:

```json
{"requestId": "...", "revision": 1, "declaredCoverageFraction": 0.5, "declaredCloudFraction": 0.1}
```

The request binding and both fractions are declared by the operator for the simulation
scenario (fractions are dimensionless, 0..1). The product package has no request binding of
its own (see [the acquisition manifest](acquisition-simulation-manifest.md)), so Product
checks via Tasking's SERVICE `/internal/requests/{id}` that the revision is current and its
status is ACCEPTED, SCHEDULED or PARTIALLY_FULFILLED before any object write.

For each source in the `MSC_SIMULATED_SOURCE_PACKAGE_V1` package, Product:

1. streams the full owned source and checks its byte count and SHA-256 against the package;
2. renders the same diagnostic byte layout as the [byte preview](simulation-product-content.md)
   (first ≤65,536 bytes, row-major U8, width ≤256, zero-padded, not Earth imagery);
3. writes the PNG, reads the stored copy back and checks its size, SHA-256, that it decodes
   with ImageIO and its width/height.

Any failure rejects the call; no L0 record or event is created. Product then writes a JSON
integrity report (`MSC_SIMULATED_L0_V1`, scope `INTEGRITY_ONLY_SYNTHETIC`,
`coverageBasis: SIMULATION_DECLARED_NOT_MEASURED`, checked/notAssessed lists, quicklooks) and
builds a domain `L0Product`:

| L0Product field | Value |
| --- | --- |
| id | `simulation-l0:{productId}` |
| acquisitionId | Acquisition manifest ID |
| instrumentSourceManifest | Package index object reference |
| packetIndexReference | `NOT_ASSESSED:whole-source-only;no-packet-model` |
| gapQualityReference | Integrity report object reference |
| ancillaryReferences | empty |

The L0 record, history, idempotent result and one `ProductQualityAssessed` outbox event commit
in one transaction. Object I/O runs outside it; retries reuse content-addressed objects. One L0
exists per package. Replay returns it without I/O. Rebinding it to another request or fractions
returns 409.

GET `/api/products/simulation-source-packages/{productId}/l0` (or `/internal/...`) returns the
record. GET `.../l0/quicklooks/{receiptId}/content` streams a quicklook PNG with
`X-MSC-Environment: SIMULATION` and `X-MSC-Purpose: SYNTHETIC_DIAGNOSTIC_QUICKLOOK_NOT_EARTH_IMAGERY`.

## Event and fulfillment

`ProductQualityAssessed` is already routed to the `msc.tasking.v1` queue in
`EventTopology.ROUTES`, so no topology change was needed. Its `QualityEvidence` payload now
has nullable `scope` and `environment` fields. Product sets them to
`INTEGRITY_ONLY_SYNTHETIC` and `SIMULATION`. `unionCoverageFraction`/`cloudFraction` carry the
declared values. Tasking applies its unchanged criteria (coverage > 0 and cloud ≤ maximum,
then partial or full against minimum coverage). It prefixes the request reason with the scope
so the UI/API shows it is not a measured result. Older producers without the fields still
deserialize. Deploy Tasking before Product: Tasking rejects unknown properties, so a new
Product event would dead-letter on an old Tasking.

The existing V1 `POST /api/requests/{id}/simulation-result` flow is unchanged.

## Resource impact

No new deployment or workload. Per call, Product holds one ≤64 KiB sample buffer and one
small PNG at a time, plus metadata for up to 64 quicklooks, within existing Product
requests/limits. The source is streamed again once per call (≤64 sources × ≤64 MiB, sequential).
Measure this with the existing Product pod metrics during a live verifier run. That has not
been done for this change.

## Verification

- `SimulationL0Test` (PostgreSQL Testcontainers, mocked owner HTTP/ObjectStorage):
  integrity-only L0 and quicklook pixels, report labels, published evidence fields,
  replay/no extra I/O, single event, rebind conflict, quicklook stream headers, corrupt source
  and undecodable stored PNG rejection, stale/closed revision rejection before writes, outbox
  rollback and retry.
- `ProductQualityFulfillmentIT` (Tasking, PostgreSQL + RabbitMQ Testcontainers, real HTTP):
  a request created through `/api/requests` moves to PARTIALLY_FULFILLED and then
  FULFILLED from broker-delivered events shaped like Product's. It also covers duplicate
  delivery and a cloud-failing assessment. Run explicitly, since surefire skips `*IT`:
  `mvn -pl services/msc-tasking-service -am test -Dtest=ProductQualityFulfillmentIT -Dsurefire.failIfNoSpecifiedTests=false`.

Not proven: Product and Tasking against each other in one deployed run, real MinIO, HTTP
role enforcement on the new Product routes, and K8s resource measurement.
