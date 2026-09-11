# Fresh Codex reviewer prompt

Review this PR as an independent reviewer.

Read:
1. linked Issue,
2. PR diff,
3. only governing architecture/ADR files needed to judge the diff.

Do not use the builder session or builder reasoning.
Do not rewrite the solution for style.

Apply `docs/ai/50-review-checklist.md`.

Report only evidence-backed findings using:
`BLOCKER | MAJOR | MINOR | NIT`

For each finding provide:
- file/area,
- failure scenario,
- why it matters,
- minimal correction direction.

If no BLOCKER/MAJOR findings exist, state that explicitly.
