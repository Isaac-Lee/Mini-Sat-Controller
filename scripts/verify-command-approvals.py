#!/usr/bin/env python3
"""Verify deployed approval authorization and missing-artifact behavior without fabricating loads."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    run = 'absent-' + str(uuid.uuid4())
    path = '/api/command-loads/' + run + '/approvals'
    request = {'checksum': 'a' * 64, 'expectedVersion': 0,
               'validUntil': {'seconds': 2000000000, 'nanos': 0, 'scale': 'TAI'}}
    for actor in ('requester', 'service'):
        call(8107, 'POST', path, request, run + actor, actor, 403)
        call(8107, 'POST', path + '/revoke', {'expectedVersion': 1}, run + actor, actor, 403)
    call(8107, 'POST', path, request, run, 'operator1', 404)
    # Local admin also has OPERATOR; it passes the role gate and reaches owner lookup.
    call(8107, 'POST', path, request, run + '-admin', 'admin', 404)
    call(8107, 'POST', path + '/revoke', {'expectedVersion': 1}, run, 'operator1', 404)
    call(8107, 'GET', '/internal/command-loads/' + run + '/approvals', expected=404)
    call(8107, 'GET', path, user='requester', expected=403)
    call(8107, 'POST', '/internal/command-loads/' + run + '/approvals', request,
         run + '-internal', 'operator1', 403)
    # Re-run the real owner-absence and existing execution-evidence regression after replacement.
    importlib.import_module('verify-command-preparation').main()
    report = {'checks': ['requester/service cannot grant or revoke human approval',
                         'operator and local admin reach missing-load rejection', 'operator missing approval rejected',
                         'missing load approval list rejected', 'requester approval read denied',
                         'internal route service boundary preserved',
                         'existing preparation and execution evidence regression passed'],
              'scope': 'Deployed denial and regression checks; positive approval lifecycle is covered by integration tests'}
    (api.ROOT / '.local/command-approval-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
