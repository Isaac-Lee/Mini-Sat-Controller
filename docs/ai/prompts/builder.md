# Builder prompt

Implement the linked MSC Issue.

Follow `AGENTS.md`.

Before editing:
1. read the Issue,
2. identify the smallest context tier from `docs/ai/30-context-budget.md`,
3. inspect only the relevant code/tests/contracts.

Then:
- implement the smallest correct change,
- add/update tests,
- run targeted validation,
- run repository-required checks available to you,
- update runtime/resource evidence when applicable,
- prepare the PR template.

Do not broaden scope.
Do not bypass a failing test.
Do not claim validation that did not run.

Finish with:
- changed behavior,
- checks actually run,
- acceptance criteria not proven,
- resource impact,
- unresolved risk.
