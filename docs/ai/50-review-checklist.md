# AI / Human Review Checklist

Review the change, not the author's style.

## Severity

- BLOCKER — unsafe/incorrect to merge; can cause mission, security, data, or severe operational failure.
- MAJOR — meaningful correctness/design defect or missing requirement; should be fixed before merge.
- MINOR — real issue with limited impact.
- NIT — optional clarity/maintainability suggestion.

## 1. Requirement correctness

- Does the diff satisfy the linked acceptance criteria?
- Does it introduce behavior outside scope?
- Are failure modes consistent with the Issue?
- Is any behavior implied only by comments rather than code/tests?

## 2. Domain and architecture

- Are DDD boundaries preserved?
- Is domain behavior located in the domain/application layer rather than infrastructure?
- Did a public contract or invariant change without documentation?
- Is a new abstraction justified by current requirements?

## 3. MSC-specific correctness

When relevant:
- units and conversions,
- coordinate/reference frames,
- UTC/time scale/timezone,
- leap-second/Earth-orientation/reference-data handling,
- orbital/geometry assumptions,
- command generation and validation,
- stale data,
- state transition legality,
- deterministic simulation,
- retry/idempotency,
- timeout/failure recovery.

## 4. Tests

- Do tests prove the behavior, not merely execute code?
- Are boundary/invalid cases covered?
- Would the test fail if the implementation regressed?
- Are important scientific/math assumptions covered by known vectors or independent reference cases where appropriate?

## 5. Runtime / Kubernetes

For deployable or workload changes:
- CPU/memory budget exists,
- request/limit assumptions are plausible,
- measurement plan exists,
- probes and shutdown are appropriate,
- resource exhaustion/backpressure behavior is considered,
- observability can identify saturation/failure.

## 6. Security / operations

- No secrets,
- boundary input validated,
- permissions least-privilege where relevant,
- destructive action protected,
- migration/rollback risk understood.

## Reviewer output

Prefer a short list of findings.

Each finding should include:
- Severity
- File/area
- Failure scenario
- Why it matters
- Minimal correction direction

Do not produce a long summary when there are no findings.
If there are no BLOCKER or MAJOR findings, state that explicitly.
