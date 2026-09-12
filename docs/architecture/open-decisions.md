# Decision register

## MSA continuation update

The user has accepted Spring Boot/Java 21, PostgreSQL with service-owned data,
HTTP plus RabbitMQ/outbox, S3-compatible storage and independent Kubernetes
Deployments. See [ADR-0011](adr/0011-msa-runtime-and-scope.md). This resolves the
previous technology-family questions OD-005, OD-006, OD-007, OD-008 and the MSA
direction in OD-010. Orekit resolves OD-003; see [ADR-0012](adr/0012-orekit.md). Concrete versions, production sizing, numerical model validation and hardware
choices remain separately tracked. The initial decision register below is retained
as history and must not override the accepted continuation.

## Initial foundation decision register

Status: **OD-001 resolved; remaining decisions open**. This register does not select the remaining technologies.
The supplied Architecture Foundation v0.1 task is the source of the constraints.

## OD-001 — Resolved: implementation language and toolchain

Accepted by the user's continuation: Java 21 LTS, Maven, JUnit 5, modular monolith.
See [ADR-0001](adr/0001-java-toolchain.md). This closes the initial blocker without
selecting any future ecosystem candidate. Java backend/core is canonical; a second
production backend language requires a separate ADR and measurable justification.

## Later technology decisions

These do not require production selections for the deterministic foundation slice.
The foundation uses deterministic fixtures and ports after resolution of OD-001.

| ID | Topic | Evidence needed before selection | Foundation boundary |
| --- | --- | --- | --- |
| OD-002 | TM/TC engine build vs buy | Mission protocol requirements, verification, licensing and adapter feasibility | Spacecraft Link port; CCSDS adapter outside core |
| OD-003 | Flight Dynamics numerical library | Accuracy, frames/time support, validation evidence and integration cost | Orbit computation port; immutable estimates |
| OD-004 | Scheduling solver | Constraint model, representative workloads and reproducibility | Planning service; MissionSchedule commit boundary |
| OD-005 | Database technologies | Atomic commit/concurrency requirements, version retention and query workload | Persistence adapters; no speculative generic repository |
| OD-006 | Event bus | Delivery, ordering, idempotency and operational requirements | Fact events; anticipate transactional outbox, no full event sourcing |
| OD-007 | Object storage | Payload throughput, retention, integrity and streaming requirements | Object storage port; manifests/references in aggregates |

## Decision timing and additional open questions

| Topic | Decision question | Resolve before |
| --- | --- | --- |
| OD-002 TM/TC | Yamcs, OpenC3, another engine, or custom implementation; which mission database semantics and verification guarantees? | First real command/telemetry integration |
| OD-003 numerical library | Does Orekit or another validated library meet accuracy, time/frame, licensing and verification requirements? | Real orbit/attitude computation |
| OD-004 solver | Which solver, or deterministic algorithm, meets measured mission constraints? | Planning beyond the fixture |
| OD-005 database | How are schedule head/version, planning artifacts and publication atomically retained under concurrency? | Durable schedule storage |
| OD-006 event bus | Is a broker needed; what delivery/order guarantees and outbox strategy? | Cross-process asynchronous consumers |
| OD-007 object storage | Which implementation meets payload streaming, retention and integrity requirements? | Real downlink/L0 byte storage |
| OD-008 web/API framework | What interface and framework meet deployment and authentication requirements? | First real API/server |
| OD-009 frontend stack | Which map/UI stack meets mission projection and operator workflows? | Production UI; TypeScript is only a future possibility |
| OD-010 Kubernetes topology | What workload measurements justify deployable boundaries and scaling? | Deployment design |
| OD-011 CCSDS library | Which standards/profiles and validated codec/transfer implementation does the mission require? | Space Link adapter implementation |
| OD-012 security/HSM | How are approvals, identity, signing keys and release evidence verified/stored? | Real command release |
| OD-013 L0 format | Which mission-specific lossless packet/raw representation, indexes and compression policy are required? | Actual instrument reconstruction |

No rows above select a product. In particular Orekit, Yamcs, OpenC3, a solver,
database, broker, object store and web framework remain unselected.

## Domain details still requiring specification

Exact fulfillment criteria, reservoir validation models, authority policy rules,
frozen-horizon replacement rules, and detailed acquisition transitions need
mission-specific decisions. Deterministic fixture assumptions must be labeled as
fixtures, never treated as production policy or physical validation.

L0 file format and instrument-specific reconstruction remain undecided. Preserve
instrument source information losslessly, remove only communication artifacts,
annotate gaps/quality, and normally retain onboard compression unless the mission
explicitly defines otherwise.
