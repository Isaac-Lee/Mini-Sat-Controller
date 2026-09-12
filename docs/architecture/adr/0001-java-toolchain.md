# ADR-0001 — Primary implementation language and build toolchain

Status: Accepted (user decision, 2026-09-11)

The original monolith-first deployment choice below is superseded by [ADR-0010](0010-independent-services.md). Java 21, Maven and JUnit 5 remain accepted.

## Context

The initial repository had no language/toolchain. The continuation explicitly
selects Java 21 LTS, Maven, JUnit 5, and a modular monolith first.

## Decision

Java 21 is the canonical backend/core implementation. Maven provides explicit,
conventional and reproducible builds; JUnit 5 is the standard test framework.
Build plugin/dependency versions and the wrapper distribution are pinned, the
compiler targets release 21, and the build requires a Java 21 JDK.

DDD bounded contexts are logical domain boundaries, not microservices by default.
Use Maven modules for major dependency directions and Java packages under `msc`
for contexts. Java was selected for strong static typing, explicit domain models,
long-term maintainability, DDD support, mature concurrency/runtime tooling,
Kubernetes/JVM observability, and future space-ground ecosystem integration.

Orekit and Yamcs are future ecosystem candidates, not adopted dependencies.
This decision selects no numerical library, TM/TC engine, solver, database,
event bus, web framework, or deployment topology.

## Consequences

TypeScript (web UI), Python (research/validation/tooling), and Rust/C++ (measured
hardware or throughput bottlenecks) are not prohibited, but are outside the
initial core architecture. Introducing a second production backend language
requires a separate ADR with measurable justification.

A JDK 21 installation is a developer prerequisite. Maven Wrapper downloads a
pinned Maven distribution; first-time dependency resolution requires network
access. No framework or application server is introduced.
