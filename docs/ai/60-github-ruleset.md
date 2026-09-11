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
- Require status checks to pass.
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

Require the validate-pr-policy job from the AI Harness Policy workflow
(.github/workflows/ai-harness-policy.yml). The PR checks view may display it as
AI Harness Policy / validate-pr-policy; select the actual emitted check context
in GitHub after its first run, with GitHub Actions as the expected source.

No product build, unit-test, or static-analysis CI exists yet. Do not configure
nonexistent checks. Add them only when real product tooling and workflows exist.
Keep ai-harness-policy.yml as the only Harness-specific CI workflow.

Before considering setup complete:
1. Enable repository Actions if needed and run the workflow on a bootstrap PR.
2. Select the emitted status check in the active default-branch ruleset.
3. Verify the PR must pass the check and resolve conversations before merging.
4. Verify force pushes and default-branch deletion are blocked.

Do not make formatting review an AI responsibility when CI can decide it.

## Risk-level enforcement and limits

Branch rules do not replace the R0-R4 process.

The included workflow checks PR metadata:
- exactly one Risk Class,
- a linked Issue reference,
- fresh Codex review recorded for R2-R4,
- Claude review recorded for R3-R4,
- human review recorded for R3-R4,
- a Remaining risk statement for R4.

R2 Claude triggers in 10-risk-levels.md must be evaluated by reviewers; the
workflow does not infer them from code. Checkboxes are self-reported evidence:
the workflow does not verify reviewer identity, freshness, Issue existence, or
the maintainer's authorization comment. Human authorization is required even
for R0-R2, where the workflow does not require the human-review checkbox.
Re-check evidence against the latest diff after changes.

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
