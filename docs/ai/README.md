# MSC AI Development Harness v0.1

This package defines the first operational AI-assisted development process for Mini-Sat-Controller (MSC).

The primary development path is:

Human / ChatGPT architecture
→ GitHub Issue
→ Codex implementation
→ CI
→ fresh Codex review
→ Claude independent review when required
→ human merge authorization
→ merge

The core design goal is not to maximize AI usage. It is to minimize duplicated context while keeping software quality, traceability, and human accountability.

## Repository baseline

This is the adapted v0.1 baseline for Isaac-Lee/Mini-Sat-Controller.
The project README is preserved at the repository root.

The repository currently has one human maintainer, @Isaac-Lee, and no product implementation.
Follow [the operating playbook](70-operating-playbook.md) for each task and
[the GitHub ruleset setup](60-github-ruleset.md) to activate branch protections.
Human merge authorization is required for every risk class. Required approving
reviews and Code Owner reviews stay disabled until a second human maintainer joins.

The AI Harness Policy Action was removed at the maintainer's request on 2026-09-13.
PR metadata and review evidence are documented manually; they are not enforced by an Action.
Add real build, test, and static-analysis checks when product tooling exists;
do not require nonexistent checks.

AGENTS.md is the primary Codex instruction file. CLAUDE.md imports it.
Keep both concise; open detailed policy only when relevant to the current task.
The package manifest is omitted because it is an installation inventory, not a
maintained repository contract.

## Files

Paths below are relative to the repository root.

- `AGENTS.md` — shared project instructions for Codex and other compatible coding agents.
- `CLAUDE.md` — imports `AGENTS.md` and adds Claude-specific review behavior.
- `.github/ISSUE_TEMPLATE/ai-task.yml` — task specification and risk classification.
- `.github/pull_request_template.md` — PR evidence, AI execution metadata, and review gates.
- `scripts/validate-ai-harness.sh` — checks the local Harness files and instruction structure.
- `.github/CODEOWNERS` — global ownership by @Isaac-Lee.
- `docs/ai/10-risk-levels.md` — R0–R4 classification and review gates.
- `docs/ai/20-model-routing.md` — GPT-first model routing and escalation policy.
- `docs/ai/30-context-budget.md` — token/context minimization rules.
- `docs/ai/40-definition-of-done.md` — MSC-specific Definition of Done.
- `docs/ai/50-review-checklist.md` — independent code-review checklist.
- `docs/ai/60-github-ruleset.md` — solo-maintainer default-branch rules and team transition.
- `docs/ai/70-operating-playbook.md` — end-to-end human/agent workflow.
- `docs/ai/80-harness-metrics.md` — lightweight evidence for tuning model/context routing.
- `docs/ai/prompts/` — minimal reusable prompts for architect, builder, and reviewers.

## v0.1 boundaries

This version intentionally does not assume:
- programming language,
- build system,
- monorepo vs multi-repo,
- Kubernetes manifest tool (raw manifests, Helm, Kustomize, etc.),
- CI test commands.

Those should be added after inspecting the actual MSC repository.

## Guiding rule

Maintain explicit engineering records, not persistent agent memory.
Do not use repository history or chat transcripts as persistent agent memory.

- Architecture → `docs/architecture/` and ADRs
- Requirements → GitHub Issues
- Work → branches and PRs
- Agent rules → `AGENTS.md` / `CLAUDE.md`
- Quality evidence → CI, tests, PR review
- Truth → code plus executable tests
