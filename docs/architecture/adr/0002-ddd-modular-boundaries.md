# ADR-0002 — DDD and modular boundaries before microservices

Status: DDD/layering accepted; modular-monolith deployment decision superseded by [ADR-0010](0010-independent-services.md) on 2026-09-11.

The decision below records the original foundation baseline. The current target is independently deployable MSA services.

## Context

MSC must keep mission intent and operations central without a framework or deployment topology dictating its model.

## Decision

Use DDD bounded-context packages under msc.domain in a modular monolith. Separate major layers with four Maven modules; do not create a module or service per noun/context. Ports depend on published domain contracts; application and infrastructure depend inward.

## Consequences

No production domain dependencies are declared. ArchUnit checks compiled dependencies. There is no server or bootstrap module until meaningful wiring requires it; test wiring is the composition root.
