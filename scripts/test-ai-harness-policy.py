#!/usr/bin/env python3
"""Exercise the workflow's embedded PR validator with real template bodies."""

import os
from pathlib import Path
import subprocess
import sys
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github/workflows/ai-harness-policy.yml").read_text()
POLICY = textwrap.dedent(WORKFLOW.split("python - <<'PY'\n", 1)[1].rsplit("          PY", 1)[0])
TEMPLATE = (ROOT / ".github/pull_request_template.md").read_text()
GATES = (
    "Fresh Codex review completed",
    "Claude independent review completed",
    "Human review completed",
)


def body_for(risk):
    body = TEMPLATE.replace("Closes #", "Closes #123").replace(
        f"- [ ] {risk} —", f"- [x] {risk} —"
    )
    for gate in GATES:
        body = body.replace(f"- [ ] {gate}", f"- [x] {gate}")
    return body


class PolicyTests(unittest.TestCase):
    def check_body(self, body, passes):
        result = subprocess.run(
            [sys.executable, "-c", POLICY],
            env={**os.environ, "PR_BODY": body}, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0 if passes else 1, result.stdout + result.stderr)

    def test_valid_risks(self):
        for risk in ("R0", "R1", "R2", "R3", "R4"):
            with self.subTest(risk=risk):
                self.check_body(body_for(risk) + "\nNone known.\n", True)

    def test_r4_template_comment_is_not_evidence(self):
        self.check_body(body_for("R4"), False)

    def test_required_gates(self):
        for risk, gates in (("R2", GATES[:1]), ("R3", GATES), ("R4", GATES)):
            for gate in gates:
                with self.subTest(risk=risk, gate=gate):
                    body = (body_for(risk) + "\nNone known.\n").replace(
                        f"- [x] {gate}", f"- [ ] {gate}"
                    )
                    self.check_body(body, False)

    def test_missing_issue(self):
        self.check_body(body_for("R1").replace("Closes #123", "Closes #"), False)

    def test_multiple_risks(self):
        self.check_body(body_for("R1").replace("- [ ] R0", "- [x] R0"), False)

    def test_commented_review_is_not_evidence(self):
        body = body_for("R3").replace(f"- [x] {GATES[0]}", f"<!-- - [x] {GATES[0]} -->")
        self.check_body(body, False)


if __name__ == "__main__":
    unittest.main()
