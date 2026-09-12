# Product downloads and synthetic byte previews

This implementation checkpoint is not yet deployed. All routes below require ADMIN,
OPERATOR or SERVICE. Requester delivery/ownership and fulfillment remain separate work.

Under `/api/products/simulation-source-packages/{productId}`:

- GET `/sources/{receiptId}/content` streams the exact owned source, with declared length
  and SHA-256 ETag. Product membership is resolved before any object read.
- GET `/index` streams the Product-owned JSON source index.
- POST `/sources/{receiptId}/preview`, with `Idempotency-Key`, creates a PNG byte preview.
- GET `/sources/{receiptId}/preview` returns its immutable metadata.
- GET `/sources/{receiptId}/preview/content` streams its PNG with length/hash ETag.

Source and index downloads also have `/internal/products/...` equivalents. Every response
is labeled SIMULATION with a purpose header and `nosniff`. The preview response purpose
explicitly says `SYNTHETIC_BYTE_PREVIEW_NOT_EARTH_IMAGERY`.

The preview is a diagnostic layout of the first at most 65,536 raw bytes, row-major U8
(grayscale), width at most 256, with a zero-padded final row when needed. Metadata records
source size/hash, exact valid sample count, dimensions, PNG size/hash, rendering convention
and georeferencing NONE. It does not infer sensor dimensions, radiometry, ground coverage,
or a calibrated observation image. Original product files are unchanged.

Before creating the PNG, the generator streams and verifies the entire source, bounded to
its declared size (at most 64 MiB). Corruption beyond the displayed prefix is still rejected.
Only the bounded sample array and small PNG are held in memory. Object reads/PNG writes
occur outside DB transactions. Metadata/history/idempotency and preview-created outbox
publication commit atomically; failed transactions can retry using content-addressed PNGs.
Successful replay does no object I/O.

`SimulationProductContentTest` uses real PostgreSQL and mocked ObjectStorage, decodes the
PNG using ImageIO and checks actual sample values, size limits, full-source hash validation,
owned source/index streaming, missing membership, corrupt/truncated/oversize input,
replay and outbox rollback. These checks do not establish HTTP role enforcement or actual
MinIO streaming for the newly added endpoints; deployed verification remains pending.

This supports viewing synthetic product bytes. A mission-qualified quicklook using an
explicit instrument raster/packet model, quality decisions and request fulfillment remain
part of the full backend goal.
