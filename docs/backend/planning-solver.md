# Planning solver proposals (greedy heuristic, SIMULATION)

`POST /api/planning/runs/{runId}/solve` (ADMIN or OPERATOR, `Idempotency-Key`
required, no body) proposes one candidate for the run's request revision using the
[ADR-0013](../architecture/adr/0013-priority-greedy-solver.md) priority-greedy
heuristic. Scope is `GREEDY_HEURISTIC_NOT_OPTIMAL`; environment is `SIMULATION`.

Inputs, all inside one transaction:

- every sibling run listed by `planning-run-index` for the run's input attempt
  (the same request on each captured spacecraft), locked in sorted spacecraft order;
- a fresh `PlanningResources` capture per run at the service clock (TAI), whose
  committed schedule heads are the conflict context and whose per-candidate
  forecast must be `VALIDATED` for the candidate to be eligible;
- the request priority captured in the input attempt (`request.requestedPriority`).

The request is rejected when its input attempt was superseded/invalidated or the
revision already has a simulation schedule. The stored `planning-solve` record and
idempotent response contain the considered run IDs, the committed context, and each
proposal with its selected candidate/run/spacecraft, `reassigned` flag and
`DecisionRecord` rationales. Replaying the same key returns the stored proposal;
it is not recomputed.

The solver writes no schedule, assignment, outbox event or request status. To act on
a proposal, the operator reviews it and calls
[`simulation-commit`](simulation-schedule-v1.md) on the proposal's `runId` with its
`candidateId`; that route re-checks resources, conflicts and versions.

Current limits: one request per call (the domain solver accepts many); proposals on
one spacecraft are not jointly re-forecast; no backtracking or optimality claim.

## Verification

PostgreSQL tests in `SimulationScheduleApiTest` cover proposal without commit,
idempotent replay after a later commit, rejection after commit and on a superseded
attempt, and role parity with `simulation-commit`. `PriorityGreedySolverTest`
covers deterministic ordering, conflict-driven reassignment to another spacecraft
(against both committed activities and earlier proposals) and ineligible options.
Cross-spacecraft reassignment is proven at domain level only; the service fixture
has a single spacecraft.
