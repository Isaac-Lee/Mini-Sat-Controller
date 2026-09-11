# MSC Definition of Done

A task is Done only when the evidence appropriate to its Risk Class exists.

## Behavior

- Linked Issue acceptance criteria are satisfied.
- No unrelated behavioral change was bundled into the PR.
- Failure behavior is defined where relevant.
- No known acceptance criterion is represented as complete without evidence.

## Tests

- Changed behavior has an executable test where practical.
- Targeted tests pass.
- Repository-required broader checks pass.
- Tests verify behavior rather than implementation details when possible.
- Simulation tests are deterministic unless nondeterminism is explicitly required.

## MSC domain correctness

When applicable:
- units are explicit,
- coordinate/reference frame is explicit,
- time scale/timezone assumption is explicit,
- boundary and stale-data cases are tested,
- state-machine transitions and invalid transitions are tested,
- retries/idempotency/failure recovery are tested,
- leap-second, EOP, ephemeris/orbit, or other reference-data assumptions are documented.

## Architecture

- DDD dependency direction is preserved.
- Domain logic is not moved into infrastructure for convenience.
- Public contract or invariant changes are documented.
- Significant architectural decisions have an ADR.

## Runtime and Kubernetes

For a new deployable service or material workload change:
- CPU request estimated,
- CPU limit estimated,
- memory request estimated,
- memory limit estimated,
- assumptions behind the estimate documented,
- measurement method defined,
- measured usage compared with estimates before the budget is considered stable.

When relevant:
- liveness/health behavior defined,
- readiness behavior defined,
- graceful shutdown tested,
- bounded queues/backpressure considered,
- logs/metrics sufficient to diagnose failure.

## Security and operations

- No secrets or credentials committed.
- External input validation exists at system boundaries.
- Destructive/shared-state operations require explicit human authorization.
- Rollback/failure recovery is described for risky operational changes.

## Review

- Review gates for R0-R4 are satisfied.
- BLOCKER and MAJOR findings are resolved or explicitly accepted by the responsible human.
- Final merge authorization is human-owned for every risk class (R0-R4).
