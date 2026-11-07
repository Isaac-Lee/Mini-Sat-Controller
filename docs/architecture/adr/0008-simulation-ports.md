# ADR-0008 — Simulation adapters use production ports

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Deterministic scenarios, replay and fault injection require replaceable external dependencies from the start.

## Decision

Use identical Clock, SpacecraftLink, GroundStation, ReferenceData and other ports for real and simulation adapters. Test composition wires an explicit virtual clock and in-memory schedule repository.

## Consequences

No alternate simulator domain or production-specific branch is introduced. Full simulators, shadow planning and physical fidelity remain future work.

## Amendment (2026-11-07) — TOY_MODEL resource integration and seeded record/replay

The simulator service may run an explicitly labelled `fidelity: TOY_MODEL`, `environment:
SIMULATION` projection (`SimulationToyPhysics`). It integrates battery (Wh), storage (MB) and
propellant (kg) per onboard tick using the existing `SimulatorOperationEffects` calculator and
the pinned mission profile, derives a ground-station link availability flag from caller-supplied
TAI visibility windows, and records a seeded event log. All randomness is drawn once into that
log; replay consumes only the log, so replaying a recorded log reproduces identical telemetry.

The toy run never advances a scenario clock or command ledger, and its telemetry is emitted
through the same simulation telemetry adapter with a `simulator:toy-model:` source. It is not a
validated power, orbit, attitude or RF model and models no link budget (dB, SNR, data rate). Its
reproducibility claim covers one log on one implementation version, not cross-simulator replay.
