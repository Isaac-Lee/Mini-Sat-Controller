#!/usr/bin/env python3
"""Verify camera model publication on an existing explicitly synthetic Planning fixture."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    fixture = json.loads((api.ROOT / '.local/planning-search-verification.json').read_text())
    run = api.call(8102, 'GET', '/api/planning/runs/' + fixture['runId'])
    craft = run['spacecraftId']
    assert craft.startswith('000-sim-search-'), 'Only explicitly synthetic fixtures may be changed'
    planning = api.call(8104, 'GET', '/internal/simulation-planning-models/' + craft)
    assert planning['body']['environment'] == 'SIMULATION'
    mission = api.call(8104, 'GET', '/internal/missions/' + craft)
    assert mission['missionDefinitionVersion'] == planning['body']['missionDefinitionVersion']
    path = '/api/simulation-camera-models'
    current = api.call(8104, 'GET', path + '/' + craft, expected=[200, 404])
    previous_version = current.get('version', 0)
    model = {'spacecraftId': craft, 'missionDefinitionVersion': mission['missionDefinitionVersion'],
             'environment': 'SIMULATION', 'pointingLaw': 'STARE_TARGET_TANGENT_PLANE_V1',
             'halfAngleAcrossDegrees': 1, 'halfAngleAlongDegrees': 1, 'rasterColumns': 4096,
             'rasterRows': 4096, 'maximumGroundSampleDistanceMeters': 5,
             'maximumOffNadirDegrees': min(30, planning['body']['maximumOffNadirDegrees']),
             'minimumTargetElevationDegrees': 10, 'simulationPlanningModelVersion': planning['version'],
             'approvalReference': 'synthetic-camera-api-verification',
             'provenance': 'Explicit synthetic fixture, no SPACEEYE instrument specifications'}
    key = 'camera-model-' + str(uuid.uuid4())
    request = {'expectedVersion': previous_version, 'model': model}
    for user in ['requester', 'operator1', 'service']:
        api.call(8104, 'POST', path, request, key, user, 403)
    first = api.call(8104, 'POST', path, request, key, 'admin')
    assert first['body'] == model and first['version'] == previous_version + 1
    assert api.call(8104, 'POST', path, request, key, 'admin') == first
    api.call(8104, 'POST', path, request, key + '-cas', 'admin', 409)
    changed = {**request, 'model': {**model, 'maximumGroundSampleDistanceMeters': 6}}
    api.call(8104, 'POST', path, changed, key, 'admin', 409)
    second_request = {**changed, 'expectedVersion': first['version']}
    second = api.call(8104, 'POST', path, second_request, key + '-v2', 'admin')
    assert second['version'] == first['version'] + 1
    assert api.call(8104, 'GET', path + '/' + craft) == second
    assert api.call(8104, 'GET', path + '/' + craft + '/versions/' + str(first['version'])) == first
    assert api.call(8104, 'GET', '/internal/simulation-camera-models/' + craft) == second
    api.call(8104, 'GET', path + '/' + craft, user='requester', expected=403)
    api.call(8104, 'GET', '/internal/simulation-camera-models/' + craft, user='operator1', expected=403)
    invalid_pin = {**model, 'simulationPlanningModelVersion': planning['version'] + 1}
    api.call(8104, 'POST', path, {'expectedVersion': second['version'], 'model': invalid_pin},
             key + '-pin', 'admin', 400)
    invalid_law = {**model, 'environment': 'FLIGHT'}
    api.call(8104, 'POST', path, {'expectedVersion': second['version'], 'model': invalid_law},
             key + '-environment', 'admin', 400)
    assert api.call(8104, 'GET', path + '/' + craft) == second
    assert api.call(8104, 'GET', '/internal/simulation-planning-models/' + craft) == planning
    assert api.call(8102, 'GET', '/api/planning/runs/' + fixture['runId']) == run
    evidence = {'spacecraftId': craft, 'simulationPlanningModelVersion': planning['version'],
                'retainedCameraVersion': first['version'], 'currentCameraVersion': second['version'],
                'checks': ['ADMIN-only publication', 'idempotent replay and CAS conflict',
                           'old immutable version retained', 'owner internal and public reads',
                           'wrong pin and flight environment rejected', 'Planning source and run preserved'],
                'scope': 'Published simulation camera model; no feasibility or command authority'}
    (api.ROOT / '.local/simulation-camera-model-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
