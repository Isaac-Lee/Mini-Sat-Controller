#!/usr/bin/env python3
"""Verify versioned authority on a new synthetic mission, without configuring NORAD 63229."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    fixture = json.loads((api.ROOT / '.local/simulation-correlation-verification.json').read_text())
    original = call(8104, 'GET', '/internal/missions/' + fixture['spacecraftId'])
    craft = 'authority-verification-' + str(uuid.uuid4())
    mission = {**original, 'spacecraftId': craft, 'provenance': 'isolated synthetic authority verification'}
    call(8104, 'POST', '/api/missions', mission, craft, 'admin')
    policy = {'spacecraftId': craft, 'missionDefinitionVersion': mission['missionDefinitionVersion'],
              'provenance': 'isolated authority API fixture', 'rules': [{
                  'context': {'actionClass': 'IMAGE', 'phase': 'ROUTINE', 'spacecraftMode': 'NOMINAL', 'risk': 'LOW'},
                  'requirement': 'TWO_PERSON_APPROVAL'}]}
    request = {'expectedVersion': 0, 'policy': policy}
    for actor in ('requester', 'operator1', 'service'):
        call(8104, 'POST', '/api/authority-policies', request, craft + actor, actor, 403)
    first = call(8104, 'POST', '/api/authority-policies', request, craft, 'admin')
    assert first['version'] == 1 and first['body'] == policy
    assert call(8104, 'POST', '/api/authority-policies', request, craft, 'admin') == first
    base = '/internal/authority-policies/' + craft
    assert call(8104, 'GET', base + '/versions/1') == first
    revised = {**policy, 'rules': [], 'provenance': 'explicit deny-all revision'}
    second = call(8104, 'POST', '/api/authority-policies', {'expectedVersion': 1, 'policy': revised}, craft + '-v2', 'admin')
    assert second['version'] == 2 and second['body'] == revised
    assert call(8104, 'GET', base) == second
    assert call(8104, 'GET', base + '/versions/1') == first
    assert call(8104, 'POST', '/api/authority-policies', request, craft, 'admin') == first
    call(8104, 'POST', '/api/authority-policies', request, craft + '-stale', 'admin', 409)
    bad = {'expectedVersion': 2, 'policy': {**policy, 'missionDefinitionVersion': 'wrong'}}
    call(8104, 'POST', '/api/authority-policies', bad, craft + '-bad', 'admin', 400)
    duplicate = {'expectedVersion': 2, 'policy': {**policy, 'rules': policy['rules'] * 2}}
    call(8104, 'POST', '/api/authority-policies', duplicate, craft + '-duplicate', 'admin', 400)
    call(8104, 'GET', '/api/authority-policies/' + craft, user='requester', expected=403)
    assert call(8104, 'GET', base) == second
    report = {'spacecraftId': craft, 'versions': [1, 2], 'checks': [
        'admin-only publication', 'original replay after update', 'exact history and current policy',
        'stale revision rejected', 'mission mismatch rejected', 'ambiguous duplicate rules rejected',
        'requester read denied', 'failed writes preserve current policy'],
        'scope': 'Deployed owner API; no Control release or physical authority qualification'}
    (api.ROOT / '.local/authority-policy-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
