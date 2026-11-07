# ADR-0013 — Priority-greedy planning proposals, not optimization

Status: Proposed on 2026-11-07 (requires human acceptance with the PR).

MSC needs multi-candidate selection and cross-spacecraft reassignment before an
operator commits a simulation schedule. A pure-domain `PriorityGreedySolver`
(`msc-domain/.../planning`) provides it with a documented, deterministic heuristic:

1. Order requests by priority (0..100, higher first), ties by request ID.
2. Order each request's options by window start (TAI), ties by candidate ID.
3. Skip options whose `FeasibilityEvaluation` is `INFEASIBLE` or whose own
   `ResourceTimeline` forecast is not `VALIDATED`.
4. Take the first option whose window does not overlap an exclusive resource on the
   same spacecraft, already used by a committed `MissionSchedule` activity or an
   earlier proposal. If the first eligible option lost to a conflict and the chosen
   one is on another spacecraft, the proposal is marked `reassigned`.
5. Every skip and the selection produce a `DecisionRecord` rationale.

The output scope is always `GREEDY_HEURISTIC_NOT_OPTIMAL`. It must not be presented
as optimal: a higher-ranked request keeps its first conflict-free choice even when
a different choice would place more requests. Results are proposals only; the solver
writes no `Assignment` and no schedule. Commitment stays with `MissionSchedule.commit`
through the existing operator-reviewed `simulation-commit` route, which re-validates
resources, conflicts, request revision and schedule version.

Known limits: per-option resource forecasts are evaluated against committed context
only, so two proposals on one spacecraft are not jointly re-forecast; there is no
backtracking, swap search or objective function. An exact or metaheuristic optimizer
would need its own ADR and its own scope label. The first API passes one request per
call, so cross-request priority ordering is exercised at domain level only.

See [planning solver behavior](../../backend/planning-solver.md).
