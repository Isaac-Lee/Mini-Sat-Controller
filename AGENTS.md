# Mini-Sat-Controller — Agent Instructions

## Mission

Build Mini-Sat-Controller (MSC) as predictable, testable, operable software.
Prefer the smallest correct change that satisfies the linked GitHub Issue.

## Source of truth

Use this order when requirements conflict:

1. Linked GitHub Issue and its acceptance criteria.
2. Accepted ADRs and architecture documents.
3. Existing public contracts and executable tests.
4. This file and relevant `docs/ai/` policy.
5. Local implementation conventions.

Do not infer a new requirement merely from old code.

## Context discipline

Minimize context before increasing model strength.

- Read the linked Issue first.
- Search for symbols and paths before opening broad directories.
- Open only files needed to understand or change the requested behavior.
- Do not scan the whole repository unless the task explicitly requires system-wide analysis.
- Do not read generated output, vendor trees, build artifacts, or lockfiles unless they are directly relevant.
- Do not use repository history or chat transcripts as persistent agent memory.
- Load detailed documents under `docs/ai/` only when the task needs them.
- At a review boundary, use a fresh session. A builder's self-review does not count as independent review.
- Do not ask a second model to re-implement the same solution unless the task is an explicit comparison experiment.

See `docs/ai/30-context-budget.md`.

## Architecture

- Preserve Domain-Driven Design boundaries.
- Domain code must not depend on infrastructure concerns.
- Keep domain invariants explicit and testable.
- Avoid speculative abstractions and future-proofing that are not required by the current Issue.
- Public contract, domain invariant, or architecture changes require documentation and may require an ADR.

## MSC correctness rules

When relevant to the changed code:

- Make units explicit.
- Make coordinate/reference frames explicit.
- Make time scale and timezone assumptions explicit. UTC is the system reference unless an accepted design says otherwise.
- Treat leap-second, Earth-orientation, orbit/ephemeris, and related reference-data changes as correctness-sensitive.
- Simulation behavior should be deterministic unless nondeterminism is an explicit requirement; seed randomness in tests.
- State-machine transitions, command validity, stale data, retries, idempotency, and failure states must be tested where applicable.

## Kubernetes and runtime budget

Kubernetes is the default deployment assumption for deployable MSC services.

For every new deployable service or material workload change:

- estimate CPU and memory requests,
- estimate CPU and memory limits,
- document workload assumptions,
- define how the estimate will be measured,
- compare measured usage with the estimate before considering the resource budget stable.

Also consider health/readiness, graceful shutdown, backpressure, and observability when applicable.

## Implementation workflow

1. Confirm the Issue has Goal, Scope, Out of Scope, Acceptance Criteria, and Risk Class.
2. Identify the smallest relevant context set.
3. Implement only the requested behavior.
4. Add or update tests that demonstrate the behavior.
5. Run targeted checks first; run broader checks required by repository policy afterward.
6. Update architecture/ADR/resource documentation only when the change requires it.
7. Open or update a PR using `.github/pull_request_template.md`.
8. Follow the review route defined by `docs/ai/10-risk-levels.md`.

## Git safety

- Human merge authorization is required for every risk class.
- Do not push directly to the protected default branch.
- Do not force-push shared branches.
- Do not bypass tests, hooks, or policy checks to make a PR pass.
- Do not delete, reset, migrate, or mutate shared/production state without explicit human authorization.
- Keep one behavioral change per PR whenever practical.

## Completion report

At the end of an implementation task, report only:

- what changed,
- tests/checks actually run and their result,
- any acceptance criterion not proven,
- resource/runtime impact if applicable,
- unresolved risks or assumptions.

Do not claim a test or validation ran unless it actually ran.

## Code review rules

Review findings must be evidence-based and tied to changed behavior.

Prioritize:
1. correctness and safety,
2. domain/architecture violations,
3. missing edge cases or tests,
4. runtime/Kubernetes risk,
5. security and operability.

Do not spend review budget on formatting or lint issues that CI can decide.

Use severity: `BLOCKER`, `MAJOR`, `MINOR`, `NIT`.
