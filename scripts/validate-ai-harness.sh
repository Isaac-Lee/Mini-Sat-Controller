#!/usr/bin/env bash
set -euo pipefail

required=(
  "AGENTS.md"
  "CLAUDE.md"
  ".github/ISSUE_TEMPLATE/ai-task.yml"
  ".github/pull_request_template.md"
  ".github/CODEOWNERS"
  "docs/ai/10-risk-levels.md"
  "docs/ai/20-model-routing.md"
  "docs/ai/30-context-budget.md"
  "docs/ai/40-definition-of-done.md"
  "docs/ai/50-review-checklist.md"
  "docs/ai/60-github-ruleset.md"
  "docs/ai/70-operating-playbook.md"
)

failed=0
for path in "${required[@]}"; do
  if [[ ! -s "$path" ]]; then
    echo "missing or empty: $path"
    failed=1
  fi
done

if grep -q '@REPLACE_WITH_MSC_OWNER' .github/CODEOWNERS; then
  echo "configuration required: replace @REPLACE_WITH_MSC_OWNER in .github/CODEOWNERS"
  failed=1
fi

lines=$(wc -l < AGENTS.md)
if (( lines > 200 )); then
  echo "AGENTS.md is $lines lines; keep root instructions under 200 lines."
  failed=1
fi

if ! grep -q '^@AGENTS.md' CLAUDE.md; then
  echo "CLAUDE.md must import @AGENTS.md to avoid duplicated policy."
  failed=1
fi

if (( failed )); then
  exit 1
fi

echo "MSC AI Development Harness validation passed."
