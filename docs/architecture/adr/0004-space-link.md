# ADR-0004 — CCSDS behind Space Link ports/adapters

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Planning chooses approved operational activities, not protocol packets.

## Decision

Keep CCSDS encoders, APIDs and transfer protocols behind SpacecraftLinkPort in infrastructure. Spacecraft Control will compile approved semantic activity templates into CommandLoad artifacts.

## Consequences

No CCSDS dependency or implementation is added. Architecture tests forbid planning transport/control imports. Actual command release requires future synchronous authority/safety gates.
