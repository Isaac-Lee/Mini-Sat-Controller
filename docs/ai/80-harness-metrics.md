# Harness Metrics

The purpose of metrics is to reduce unnecessary agent usage without lowering quality.

Do not optimize for the smallest model in isolation.
Optimize for the least total rework needed to reach a correct merge.

## Record per PR

The PR template already records:
- Risk Class,
- builder model,
- reasoning effort,
- whether escalation occurred,
- fresh Codex review,
- Claude review,
- remaining risk.

When useful, add one short line under `AI execution`:

`Implementation passes: N`

Count a pass when the builder receives a new implementation/repair instruction, not every tool call.

## Weekly review

For recently merged AI-assisted PRs, inspect:

- R0/R1 work that used Sol/Astra without an escalation reason,
- R2 work that repeatedly needed more than two implementation passes,
- R3/R4 work where Claude found MAJOR/BLOCKER issues after Codex review,
- PRs that loaded broad context but changed only a small area,
- repeated review findings that could become CI checks,
- post-merge defects attributable to insufficient review or incorrect Risk Class.

## Decisions

Use the evidence to change routing, not anecdotes.

Examples:
- If Terra completes a class of R2 tasks reliably in one pass, keep it as default.
- If a task class repeatedly escalates from Terra to Sol, route that class directly to Sol.
- If Claude rarely adds value for a specific R2 subtype, remove the trigger.
- If Claude repeatedly finds the same issue, encode the rule in tests/CI or a narrow agent rule.
- If root instructions grow, move specialized rules closer to the code or into on-demand documentation.

## Guardrail

A cheaper first pass is not an optimization when it predictably causes multiple repair loops.
Measure total path-to-merge cost and defect rate.
