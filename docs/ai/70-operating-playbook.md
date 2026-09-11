# MSC AI Development Operating Playbook v0.1

## 1. Shape the Issue

Use ChatGPT as architect/product engineering support.

The Issue must contain:
Goal, Minimal Context, Scope, Out of Scope, Acceptance Criteria, Risk Class, Affected Components, Runtime Impact, Validation Plan.

Do not start coding if the acceptance criteria cannot distinguish success from failure.

## 2. Route the implementation

Use `docs/ai/10-risk-levels.md` and `20-model-routing.md`.

Default:
- R0 Luna
- R1 Terra
- R2 Terra
- R3 Sol
- R4 Sol

A stronger model is an escalation, not a reward for a large prompt.

## 3. Build in a branch

The builder:
- reads the Issue first,
- uses the smallest context tier,
- implements one behavioral change,
- adds tests,
- runs targeted checks,
- records real validation results.

Create a Draft PR early for R2-R4 when useful, because the PR becomes the shared coordination object.

## 4. Fresh Codex review

Required for R2-R4.

Start a new Codex session.
Do not continue the builder session.

Give the reviewer:
- Issue,
- diff,
- only relevant governing docs.

Apply `docs/ai/50-review-checklist.md`.

## 5. Claude independent review

Required for R3-R4.
Selective for R2 according to the trigger list.

Start Claude review in a fresh agent context, separate from the builder session.
Claude should receive the same evidence boundary:
Issue + diff + minimal governing docs.

Do not forward the builder's conversation transcript.

## 6. Repair loop

Send concrete findings back to the builder.

Prefer:
finding → focused patch → affected tests → reviewer re-check

Avoid:
reviewer transcript → full reimplementation → another broad review

If one focused repair does not resolve a MAJOR/BLOCKER, consider:
- splitting the Issue,
- raising reasoning effort,
- escalating the model,
- revisiting architecture/acceptance criteria.

## 7. Human review

A human owns merge authorization for every risk class. During the solo-maintainer
phase, @Isaac-Lee inspects the final diff and checks, records authorization in a
PR comment identifying the reviewed head commit, and performs the merge.
A PR-body checkbox alone is not authorization. See [ruleset setup](60-github-ruleset.md).

For R3/R4:
- inspect unresolved risks,
- verify AI review gates,
- confirm CI,
- confirm runtime/resource impact,
- verify operational/rollback implications where relevant.

## 8. Merge and learn

After merge, only durable learnings are added to agent instructions.

Update `AGENTS.md` only when the same correction should apply to most future tasks.
Put path-specific rules near the affected code instead of making the root instructions grow indefinitely.

If a review repeatedly catches the same mistake:
1. decide whether CI can enforce it,
2. if yes, automate it,
3. otherwise add a concise agent rule,
4. remove obsolete rules.

This keeps the instruction surface small and token-efficient.
