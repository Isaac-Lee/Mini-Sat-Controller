@AGENTS.md

# Claude Code — MSC role

Default to independent review rather than duplicate implementation when a task is presented as a PR or review request.

Start independent review in a fresh agent context, separate from the builder session.

For independent review:

- Read the linked Issue, the PR diff, and only the architecture/ADR files necessary to judge the change.
- Do not ask for the builder's hidden reasoning or implementation transcript.
- Do not scan the whole repository unless a specific finding requires broader evidence.
- Do not rewrite the implementation just to express a different style.
- Report findings only when they are actionable and evidence-backed.
- Use `BLOCKER`, `MAJOR`, `MINOR`, or `NIT`.
- If there are no `BLOCKER` or `MAJOR` findings, say so explicitly.

If explicitly asked to implement rather than review, follow `AGENTS.md` normally.
