# Model Routing

## Principle

Use the smallest sufficient model and the smallest sufficient context.

MSC is GPT-first:
- ChatGPT / GPT-5.6 Sol: architecture, requirements, task decomposition, ADR reasoning.
- Codex GPT-5.6 Luna: mechanical and low-risk repetitive work.
- Codex GPT-5.6 Terra: default implementation model.
- Codex GPT-5.6 Sol: system, domain, and difficult implementation work.
- GPT-6 Astra: escalation for unusually difficult or long-horizon work; not the default.
- Claude Code: independent reviewer, not a second default implementer.

## Default route

R0 → Luna
R1 → Terra
R2 → Terra, then Sol only when triggered
R3 → Sol
R4 → Sol; Astra only when escalation criteria are met

## Reasoning effort

Start lower than instinct suggests.

- Low: mechanical edits, simple local behavior.
- Medium: normal feature implementation, bounded domain logic.
- High: cross-service, concurrency, non-trivial state machines, scientific/math-sensitive logic.
- Extra High/Max: only when high-effort work has a concrete unresolved blocker.

Raising reasoning effort is preferred before switching to a second full implementation model when the problem is still within the same context.

## Claude usage

Use Claude to create disagreement, not duplicate labor.

Give Claude:
1. linked Issue,
2. PR diff,
3. relevant ADR/architecture documents,
4. failing/passing CI evidence when material.

Do not give Claude:
- the builder's long chat transcript,
- the builder's hidden reasoning,
- repository-wide context "just in case",
- unrelated historical discussions.

## Failed-attempt budget

For a normal task:
- one primary implementation pass,
- one focused repair pass after concrete test/review evidence,
- then escalate or split the Issue.

Do not spend repeated agent turns polishing a design whose acceptance criteria are still unclear. Return to the Issue instead.

## Current model note

This routing was authored for the September 2026 GPT-5.6 family in Codex (Luna, Terra, Sol) with GPT-6 Astra available as an escalation option. Re-evaluate names/availability when the platform changes; preserve the R0-R4 intent.
