#!/usr/bin/env python3
"""Run the bounded V1 backend review against the existing local K8s environment."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    commands = [
        ['scripts/verify-public-tle.py'],
        ['scripts/verify-planning-search.py', '--with-runs', '--with-camera',
         '--with-simulation-commit', '--with-simulation-dispatch', '--with-v1-downlink',
         '--with-v1-result', '--timeout-seconds', '180'],
        ['scripts/verify-safety.py'],
        ['scripts/verify-k8s.py'],
    ]
    for command in commands:
        print('Running ' + ' '.join(command), flush=True)
        subprocess.run([sys.executable, *command], cwd=ROOT, check=True)
    print('V1 review passed. Saved result: .local/v1-functional-verification.json')


if __name__ == '__main__':
    main()
