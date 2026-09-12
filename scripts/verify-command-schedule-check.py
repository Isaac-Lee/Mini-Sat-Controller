#!/usr/bin/env python3
"""Verify deployed current-schedule boundaries without inventing a committed schedule."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    run = 'missing-schedule-' + str(uuid.uuid4())
    tai = lambda seconds: {'seconds': seconds, 'nanos': 0, 'scale': 'TAI'}
    key = {'spacecraftId': {'value': run}, 'horizon': {'start': tai(1000), 'end': tai(2000)}}
    call(8102, 'POST', '/api/planning/schedules/current', key, user='requester', expected=403)
    call(8102, 'POST', '/internal/planning/schedules/current', key, expected=404)
    path = '/api/command-loads/' + run + '/schedule-check'
    call(8107, 'POST', path, user='requester', expected=403)
    call(8107, 'POST', path, user='operator1', expected=404)
    call(8107, 'POST', '/internal/command-loads/' + run + '/schedule-check', expected=404)
    importlib.import_module('verify-command-approvals').main()
    report = {'checks': ['Planning requester denied', 'missing current schedule rejected',
                         'Control requester denied', 'missing prepared load rejected',
                         'internal check route available', 'approval/preparation/evidence regression passed'],
              'scope': 'Deployed rejection boundaries; positive current-schedule comparisons remain unit-tested'}
    (api.ROOT / '.local/command-schedule-check-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
