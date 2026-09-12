# ADR-0011 — MSA runtime, data ownership and completion scope

Status: Accepted by user, 2026-09-11.

## Decision

Implement the complete backend operational flow through real HTTP APIs, durable
service-owned PostgreSQL data, independent Java 21 / Spring Boot services,
synchronous HTTP, asynchronous RabbitMQ with transactional outbox/inbox, and
S3-compatible object storage. Package independently runnable images and Kubernetes
Deployments so selected services can scale horizontally. External spacecraft and
ground stations are simulator adapters for integration verification.

Do not redefine completion as a scaffolding, collection of CRUD endpoints, or
in-process fixture. Verify request-to-fulfillment across independent processes,
restart persistence, concurrent writers, message retries, duplicate commands,
unknown outcomes and selective replica scaling. Preserve domain time/provenance
and never infer successful execution merely from successful transmission.

## Ownership

Each service owns a separate database and credential, migrations, transaction
boundary, API and event consumers. A development PostgreSQL cluster may host
multiple databases; no service queries another service's tables. Outbox is written
with domain state; consumer inbox is written with local effects. Publisher confirms
and consumer acknowledgements are separate requirements.

Source: [RabbitMQ acknowledgements and confirms](https://www.rabbitmq.com/docs/confirms).
Database work claiming will use explicit row locks/atomic constraints as supported
by [PostgreSQL SELECT locking](https://www.postgresql.org/docs/current/sql-select.html).
The domain remains independent of these libraries and contracts remain versioned.

## Scope limits requiring explicit future decisions

Real equipment protocols, mission-qualified numerical models, instrument-specific
L0 representation and HSM remain unselected. Simulator models and data must be
labeled as such. If a required production behavior cannot be specified without
one of these architecture/operations decisions, raise that decision rather than
claiming the entire backend complete from simulation-only evidence.

Implementation versions are pinned in Maven/deployment manifests and verified
against their published artifacts. No public cloud deployment is authorized by
this local implementation/testing task.
