# ADR-0006 — Immutable estimates and operational designation

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Promoting an estimate by mutating it loses historical meaning and planning provenance.

## Decision

OrbitSolution, PropellantEstimate and ephemeris context are immutable. OperationalDesignation points to an OrbitSolution ID and changes through a new pointer version. Retain epoch, frame, uncertainty, model/configuration and source references.

## Consequences

Designation changes require a future atomic publication gate. No numerical algorithm/library is selected. The pattern will extend to propellant and time correlation when needed.
