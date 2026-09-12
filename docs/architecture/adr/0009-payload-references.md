# ADR-0009 — Large payload bytes outside aggregates

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Payload throughput and reconstruction have different resource requirements from TT&C and aggregate consistency.

## Decision

Acquisition and L0Product retain typed IDs, manifests and evidence references. Stream bytes through ObjectStoragePort; events contain references only.

## Consequences

No image bytes, file format or object storage implementation is selected. Preserve lossless instrument source information, annotate gaps/quality and normally retain onboard compression; quicklook and fulfillment are separate future stages.
