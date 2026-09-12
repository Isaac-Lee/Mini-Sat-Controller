# GitHub Ruleset — MSC v0.1

Repository: Isaac-Lee/Mini-Sat-Controller.
Target the default branch (currently main). These instructions do not configure
GitHub automatically.

## Solo-maintainer phase

Create an active branch ruleset in Settings → Rules → Rulesets targeting the
default branch, with no routine bypass actors.

Enable:
- Require a pull request before merging.
- Require conversation resolution before merge.
- Require status checks only when an actual retained workflow emits them.
- Block force pushes.
- Restrict deletions (block deletion of the protected default branch).

Keep disabled:
- Require approving review: required approvals must remain 0.
- Require Code Owner review (Require review from Code Owners).
- Require approval of the most recent reviewable push.

The sole maintainer cannot approve their own PR. Do not enable approval gates
that would prevent routine PRs from merging in this phase.
Feature branches may still be deleted after merge; the deletion rule targets
the protected default branch.

Human merge authorization remains required for every risk class (R0-R4).
@Isaac-Lee must inspect the final diff, current CI, review evidence, and remaining
risks, then record authorization in a PR comment identifying the reviewed head
commit and perform the merge. New commits require renewed authorization.
Agents must not merge solely because checks or PR-body checkboxes are green.
This is a human process gate, not an approving-review rule enforced by GitHub.

## Required status checks

The maintainer removed the AI Harness Policy Action on 2026-09-13. Do not
require `validate-pr-policy` or `AI Harness Policy / validate-pr-policy` in a
branch rule: the workflow no longer emits that check. If a previously configured
ruleset still requires it, remove that check context to avoid permanently pending PRs.

No product build, unit-test, or static-analysis CI exists yet. Do not configure
nonexistent checks. Add them only when real product tooling and workflows exist.
`scripts/validate-ai-harness.sh` remains a local structure check; the removed
workflow's embedded-validator regression test was removed with its implementation.

Before considering setup complete, verify the actual active branch rules and
retained check contexts. Verify conversation resolution, force-push protection and
default-branch deletion protection where configured. This document itself does
not establish that remote protection is enabled.

## Risk-level enforcement and limits

Review guidance in `10-risk-levels.md`, Issue/PR templates and human merge
authorization remain in place. Removing a metadata Action does not establish that
tests or reviews occurred. Record actual review evidence and outstanding limitations
truthfully; do not check a human-review box merely to make a status check pass.

## When a second human maintainer joins

Verify both humans have appropriate repository access and update CODEOWNERS so
another eligible human can review changes (including PRs authored by @Isaac-Lee).
Then enable both:
- Require approving review, with at least 1 approval.
- Require Code Owner review.

Also enable dismissal of stale approvals when new commits are pushed.
Keep all existing PR, status-check, conversation, force-push, deletion, and human
authorization requirements. An AI review never substitutes for human approval.

## CODEOWNERS

The global owner is @Isaac-Lee. Ownership is declared now; required Code Owner
review remains disabled during the solo-maintainer phase.

Add path-specific owners when the structure and team justify them, especially
for architecture/ADRs, deployment, command/control, mission execution,
time/orbit/reference data, simulators, and security configuration.

## Merge strategy

Prefer squash merge for one-Issue/one-behavior PRs.

## GitHub references

- [Available rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)
- [Creating rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository)
