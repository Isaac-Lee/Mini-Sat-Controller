# ADR-0005 — Observation is not state

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Telemetry can arrive late/out of order and ground contact does not continuously observe execution.

## Decision

Preserve observedAt, receivedAt, source and quality in TelemetryObservation. Keep SpacecraftOperationalState as a separate application read model with freshness/evidence. Execution belief, confirmation, failure and UNKNOWN remain distinct.

## Consequences

No telemetry arrival automatically replaces state or confirms execution. Onboard reconciliation detects mismatching IDs but is not a full evidence projection algorithm.
