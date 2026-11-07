# ADR-0004 — CCSDS behind Space Link ports/adapters

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Planning chooses approved operational activities, not protocol packets.

## Decision

Keep CCSDS encoders, APIDs and transfer protocols behind SpacecraftLinkPort in infrastructure. Spacecraft Control will compile approved semantic activity templates into CommandLoad artifacts.

## Consequences

No CCSDS dependency or implementation is added. Architecture tests forbid planning transport/control imports. Actual command release requires future synchronous authority/safety gates.

## Notes

2026-11-07: `CommandReleaseGate` (spacecraft-control service) is now the only caller of
`SpacecraftLinkPort` (a convention; no architecture test enforces it yet). It refuses any
non-allowed `CommandReleasePolicy.Decision` or a load whose binding differs from the checked one,
commits durable intent before `transmit`, and classifies transport uncertainty as UNCONFIRMED for
receiver-side reconciliation. Adapter validation failures (for example a mismatched ledger)
propagate after intent is committed; the next dispatch re-observes the receiver first. The only adapter,
`SimulationSpacecraftLink`, is SIMULATION: it posts the released JSON submission to the Simulator
service. It is **not CCSDS** — no frames, APIDs, transfer protocol or RF. CCSDS stays behind this
port and must not be added without conformance vectors. The V1 simulation decision is built from
the live schedule/authority checks; it does not evaluate resource validation or the full
`CommandReleasePolicy.evaluate` context, so it is not an operational release fence.
