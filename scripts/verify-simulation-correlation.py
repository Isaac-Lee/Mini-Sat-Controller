#!/usr/bin/env python3
"""Verify deployed correlation ownership/history using an isolated synthetic mission."""
import copy
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    run = 'correlation-verification-' + str(uuid.uuid4())
    missions = call(8104, 'GET', '/internal/missions')
    assert missions, 'Seed a simulation mission/catalog before running this verifier'
    mission = copy.deepcopy(missions[0]['body'])
    mission.update(spacecraftId=run, missionDefinitionVersion=run,
                   timeCorrelationId=run, provenance='synthetic-api-verification')
    call(8104, 'POST', '/api/missions', mission, run, 'admin')
    tai = lambda seconds: {'seconds': seconds, 'nanos': 0, 'scale': 'TAI'}
    correlation = {
        'spacecraftId': run, 'missionDefinitionVersion': run, 'timeCorrelationId': run,
        'clockPartition': 'synthetic-clock', 'taiEpoch': tai(1000000),
        'tickEpoch': 500, 'ticksPerSecond': 10,
        'validInterval': {'start': tai(0), 'end': tai(10000000)},
        'environment': 'SIMULATION', 'approvalReference': 'synthetic-api-verification-v1',
        'provenance': 'API verification fixture; not SPACEEYE-T1 hardware parameters'}
    path = '/api/simulation-time-correlations'
    request = {'expectedVersion': 0, 'correlation': correlation}
    for user in ['requester', 'operator1', 'service']:
        call(8104, 'POST', path, request, run, user, expected=403)
    first = call(8104, 'POST', path, request, run, 'admin')
    assert first['version'] == 1 and first['body'] == correlation
    assert call(8104, 'POST', path, request, run, 'admin') == first
    changed = copy.deepcopy(request)
    changed['correlation']['approvalReference'] = 'synthetic-api-verification-v2'
    call(8104, 'POST', path, changed, run, 'admin', expected=409)
    call(8104, 'POST', path, request, run + '-stale', 'admin', expected=409)
    changed['expectedVersion'] = 1
    second = call(8104, 'POST', path, changed, run + '-v2', 'admin')
    assert second['version'] == 2 and second['body'] == changed['correlation']
    for user in ['admin', 'operator1', 'service']:
        assert call(8104, 'GET', path + '/' + run, user=user) == second
        assert call(8104, 'GET', path + '/' + run + '/versions/1', user=user) == first
    assert call(8104, 'GET', '/internal/simulation-time-correlations/' + run + '/versions/1') == first
    call(8104, 'GET', path + '/' + run, user='requester', expected=403)
    call(8104, 'GET', path + '/' + run + '/versions/999', expected=404)
    invalid = copy.deepcopy(changed)
    invalid['expectedVersion'] = 2
    invalid['correlation']['timeCorrelationId'] = 'wrong-mission-binding'
    call(8104, 'POST', path, invalid, run + '-invalid', 'admin', expected=400)
    assert call(8104, 'GET', path + '/' + run) == second
    report = {
        'spacecraftId': run, 'versions': [1, 2],
        'checks': ['admin-only publication', 'idempotent replay and changed-body conflict',
                   'stale version conflict', 'exact historical and current reads',
                   'read roles and missing version', 'mission binding rejects without mutation'],
        'scope': 'Deployed owner API persistence and authorization; conversion math is tested in Maven'}
    (api.ROOT / '.local/simulation-correlation-verification.json').write_text(
        json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
