# ADR-0008 — Simulation adapters use production ports

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Deterministic scenarios, replay and fault injection require replaceable external dependencies from the start.

## Decision

Use identical Clock, SpacecraftLink, GroundStation, ReferenceData and other ports for real and simulation adapters. Test composition wires an explicit virtual clock and in-memory schedule repository.

## Consequences

No alternate simulator domain or production-specific branch is introduced. Full simulators, record/replay, shadow planning and physical fidelity remain future work.
