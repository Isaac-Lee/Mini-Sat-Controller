#!/usr/bin/env python3
"""Verify persistent scenario initialization against deployed local owner services."""
import copy
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    # Reuse the isolated mission made by the correlation verifier; never reconfigure NORAD 63229.
    fixture = json.loads((api.ROOT / '.local/simulation-correlation-verification.json').read_text())
    craft = fixture['spacecraftId']
    mission = call(8104, 'GET', '/internal/missions/' + craft)
    correlation = call(8104, 'GET', f'/internal/simulation-time-correlations/{craft}/versions/2')
    profiles = call(8104, 'GET', '/internal/operation-resource-profiles/' + craft, expected=[200, 404])
    run = 'scenario-' + str(uuid.uuid4())
    if 'body' not in profiles:
        catalog = call(8104, 'GET', f"/internal/catalog/{mission['catalogId']}/versions/{mission['catalogVersion']}")
        assert catalog['template']['operation'] == 'IMAGE', 'Fixture requires an existing IMAGE catalog'
        profiles = call(8104, 'POST', '/api/operation-resource-profiles', {
            'expectedVersion': 0, 'profiles': {
                'spacecraftId': craft, 'missionDefinitionVersion': mission['missionDefinitionVersion'],
                'environment': 'SIMULATION', 'approvalReference': 'synthetic-api-verification',
                'provenance': 'isolated scenario test fixture', 'profiles': [{
                    'operation': 'IMAGE', 'catalog': {'catalogId': catalog['id'], 'catalogVersion': catalog['version']},
                    'expected': catalog['resources'], 'downlinkMegabytesPerSecond': None}]}},
            run + '-profiles', 'admin')
    request = {'id': str(uuid.uuid4()), 'spacecraftId': craft, 'correlationVersion': 2,
               'resourceProfilesVersion': profiles['version'], 'initialTick': correlation['body']['tickEpoch'],
               'reservoirs': {'storedMegabytes': 0, 'propellantKilograms': mission['propellantKg']},
               'provenance': 'isolated deployed scenario verification; not hardware truth'}
    for actor in ['service', 'operator1', 'requester']:
        call(8114, 'POST', '/api/simulation/scenarios', request, run, actor, expected=403)
    saved = call(8114, 'POST', '/api/simulation/scenarios', request, run, 'admin')
    assert saved == call(8114, 'POST', '/api/simulation/scenarios', request, run, 'admin')
    assert saved == call(8114, 'GET', '/internal/simulation/scenarios/' + request['id'])
    assert saved == call(8114, 'GET', '/api/simulation/scenarios/' + request['id'], user='admin')
    body = saved['body']
    assert body['environment'] == 'SIMULATION' and body['mission'] == mission
    assert body['correlation'] == correlation and body['resourceProfiles'] == profiles
    assert body['currentTick'] == request['initialTick'] and body['reservoirs'] == request['reservoirs']
    call(8114, 'POST', '/api/simulation/scenarios', request, run + '-duplicate', 'admin', expected=409)
    changed = copy.deepcopy(request)
    changed['initialTick'] += 1
    call(8114, 'POST', '/api/simulation/scenarios', changed, run, 'admin', expected=409)
    for actor in ['operator1', 'requester']:
        call(8114, 'GET', '/api/simulation/scenarios/' + request['id'], user=actor, expected=403)
    assert saved == call(8114, 'GET', '/internal/simulation/scenarios/' + request['id'])
    report = {'scenarioId': request['id'], 'spacecraftId': craft,
              'checks': ['admin-only creation', 'exact cross-service source snapshots',
                         'durable diagnostic reads', 'idempotent replay',
                         'duplicate and changed-request conflicts', 'read role enforcement'],
              'scope': 'Persistent simulation initialization only; no command execution'}
    (api.ROOT / '.local/simulation-scenario-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
