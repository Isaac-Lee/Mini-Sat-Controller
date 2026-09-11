# Context Budget

Context is a resource budget, just like CPU and memory.

The goal is not "give the agent everything."
The goal is "give the agent enough evidence to make the next correct decision."

## Context tiers

### C0 — Patch context
Use for R0.
- Issue
- target file(s)
- nearby tests if needed

### C1 — Component context
Use for R1.
- Issue
- target component/package
- direct interface
- relevant tests

### C2 — Domain context
Use for R2.
- Issue
- relevant bounded-context/domain docs
- target code
- direct callers/callees
- related ADR only if it governs the change

### C3 — System context
Use for R3/R4.
- Issue
- architecture/context-map section that owns the flow
- affected service contracts
- relevant deployment/runtime policy
- correctness-sensitive reference docs

C3 still does not mean "read the repository."

## Exploration protocol

1. Read the Issue.
2. Search names/symbols/paths.
3. Open the narrowest likely implementation path.
4. Trace only direct dependencies needed to establish behavior.
5. Stop exploring when the acceptance criteria can be mapped to code and tests.
6. Expand context only after encountering contradictory evidence.

## Token anti-patterns

Avoid:
- pasting the entire product conversation into coding prompts,
- asking two models to independently implement the same task,
- repeatedly reopening unchanged files,
- loading every ADR,
- preloading architecture documents on every session,
- asking a reviewer to understand the entire repository,
- sending generated code, lockfiles, vendored sources, or binary-derived text unless directly relevant,
- retaining a long implementation session for independent review.

## Review isolation

A fresh reviewer receives:
Issue + diff + minimal governing docs.

Implementation reasoning is intentionally excluded.
This reduces anchoring and context duplication.

## Durable state

Maintain explicit, current engineering records. Do not use repository history
or chat transcripts as persistent agent memory. Consult specific historical
evidence only when needed for the current task; do not preload it across sessions.

- architectural decision → ADR,
- requirement → Issue,
- behavior → code/test,
- resource budget → versioned deployment/runtime document,
- review result → PR review/comment.

Do not create permanent documents for transient debugging notes unless the result is reusable engineering knowledge.
