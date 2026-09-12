# ADR-0010 — Independently deployable backend services

Status: Accepted direction (user decision, 2026-09-11); infrastructure and scope resolved in ADR-0011; topology refined through implementation.

## Context

The user now requires an MSA backend with many independently deployable services,
so Kubernetes can scale only the services whose workload grows. This supersedes
the earlier modular-monolith-first deployment decision. It does not supersede DDD,
time, provenance, command safety, or schedule consistency requirements.

## Decision

Target independent backend processes with explicit contracts, service-owned state,
independent images/configuration and Kubernetes Deployments. Keep the canonical
Java 21 backend language and framework-free domain. Do not treat multiple replicas
of one process with a shared in-memory map as a distributed consistency solution.

Service boundaries follow operational ownership and workload characteristics.
Stateless API replicas and worker replicas may scale horizontally; consistency
boundaries need atomic database operations, partition ownership or fencing.
MissionSchedule commit and command release remain synchronous safety gates.
Events carry committed facts and support idempotent asynchronous consumers.

## Proposed implementation topology (not yet accepted deployment contracts)

Tasking, planning/schedule authority, flight dynamics, spacecraft control/release,
monitoring, anomaly/safety, ground operations, acquisition/accounting, product
production, mission definition, reference data, and mission projections can be
independent services. Space Link and simulator adapters are independently bounded
workers/processes. Final grouping must preserve atomic ownership rather than split
safety predicates across eventually consistent decision-makers.

An MSA release design must specify how a schedule validation/version reservation,
current safety state and approvals remain valid across the irreversible send
boundary. A stale event projection or process-local lock is insufficient.

## Consequences and pending decisions

ADR-0002's DDD/inward dependency rules remain applicable; its monolith deployment
constraint is superseded. Core modules can be reused during incremental extraction,
but each deployable must have a meaningful independent API/worker lifecycle and
must not directly access another service's data store.

The user accepted the runtime/framework, database, broker, object storage and
simulator integration scope in [ADR-0011](0011-msa-runtime-and-scope.md). Kubernetes manifests, resources,
scaling tests and end-to-end service integration remain required work; this ADR
alone does not complete the requested backend.
