#!/usr/bin/env python3
"""Real HTTP/broker verification of planning intake and explicit simulation agility inputs."""
import importlib
import json
from pathlib import Path
import time
import uuid

call = importlib.import_module('verify-public-orbit').call
ROOT = Path(__file__).resolve().parents[1]


def wait_for(path, predicate, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = call(8102, 'GET', path, expected=[200, 404])
        if predicate(value):
            return value
        time.sleep(.5)
    raise AssertionError(('Timed out waiting for planning', path, value))


def main():
    run = uuid.uuid4().hex
    craft = 'sim-agility-' + run
    catalog = {'id': 'sim-imaging-' + run, 'version': 1,
               'activity': {'id': {'value': 'sim-imaging'}, 'version': 1, 'name': 'IMAGING_STRIP',
                            'approved': True, 'exclusiveResources': [{'value': 'payload'}],
                            'allowedPhases': ['ROUTINE'], 'allowedModes': ['NOMINAL'],
                            'riskClass': 'LOW', 'commandTemplateReference': 'sim-imaging:1'},
               'template': {'id': 'sim-imaging', 'version': 1, 'operation': 'IMAGE', 'parameters': {}},
               'resources': {'powerWatts': 20, 'generatedMegabytes': 1, 'propellantKilograms': 0},
               'authority': 'AUTO_ALLOWED', 'durationSeconds': 10,
               'approvalReference': 'explicit-synthetic-local-test'}
    call(8104, 'POST', '/api/catalog', catalog, run, 'admin')
    profile = {'spacecraftId': craft, 'catalogId': catalog['id'], 'catalogVersion': 1,
               'missionDefinitionVersion': 'sim-v1', 'batteryCapacityWh': 100,
               'minimumBatteryWh': 20, 'storageCapacityMb': 1000, 'propellantKg': 1,
               'rechargeWatts': 10, 'timeCorrelationId': 'simulation-time',
               'provenance': 'synthetic-test-not-SPACEEYE-hardware'}
    call(8104, 'POST', '/api/missions', profile, run, 'admin')
    propellant_model = {'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
                        'telemetryBindingVersion': 1, 'absoluteUncertaintyKg': .1,
                        'maximumUnmodeledLossKgPerSecond': .000001, 'maximumPropagationSeconds': 172800,
                        'approvalReference': 'explicit-synthetic-local-test'}
    propellant_publish = {'expectedVersion': 0, 'model': propellant_model}
    call(8104, 'POST', '/api/propellant-models', propellant_publish, run, 'requester', 403)
    propellant_configuration = call(8104, 'POST', '/api/propellant-models', propellant_publish, run, 'admin')
    assert call(8104, 'POST', '/api/propellant-models', propellant_publish, run, 'admin') == propellant_configuration
    call(8104, 'POST', '/api/propellant-models', propellant_publish, run+'prop-stale', 'admin', 409)
    model = {'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
             'maximumOffNadirDegrees': 30, 'slewRateDegreesPerSecond': 2,
             'settlingSeconds': 5, 'approvalReference': 'explicit-synthetic-local-test'}
    body = {'expectedVersion': 0, 'model': model}
    call(8104, 'POST', '/api/agility-models', body, run, 'requester', 403)
    first = call(8104, 'POST', '/api/agility-models', body, run, 'admin')
    assert call(8104, 'POST', '/api/agility-models', body, run, 'admin') == first
    call(8104, 'POST', '/api/agility-models', body, run+'stale', 'admin', 409)
    second = call(8104, 'POST', '/api/agility-models',
                  {'expectedVersion': 1, 'model': {**model, 'settlingSeconds': 8}}, run+'v2', 'admin')
    assert second['version'] == 2
    assert call(8104, 'GET', '/internal/agility-models/'+craft+'/versions/1') == first
    area = {'id': 'test-area', 'west': 127.3, 'south': 36.3, 'east': 127.4, 'north': 36.4,
            'sourceReference': 'explicit-test-coordinates'}
    now = int(time.time()) + int(importlib.import_module('verify-public-orbit').ENV['MSC_TIME_OFFSET_SECONDS'])
    tai = lambda seconds: {'seconds': seconds, 'nanos': 0, 'scale': 'TAI'}
    binding = {'spacecraftId': craft, 'version': 1, 'source': 'simulator:'+run, 'environment': 'SIMULATION',
               'maximumAgeSeconds': 300, 'futureSkewSeconds': 0, 'approvalReference': 'synthetic-test'}
    call(8109, 'POST', '/api/telemetry-bindings', binding, run, 'admin')
    frame = {'id': str(uuid.uuid4()), 'spacecraftId': craft, 'bindingVersion': 1, 'source': binding['source'],
             'sequence': 1, 'observedAt': tai(now), 'quality': 'GOOD', 'mode': 'NOMINAL',
             'batteryWh': 100, 'storageMb': 1, 'propellantKg': 1, 'provenance': 'explicit-synthetic-mass-test'}
    call(8114, 'POST', '/api/simulation/telemetry', frame, run, 'admin')
    for _ in range(100):
        telemetry = call(8109, 'GET', '/internal/spacecraft-estimates/'+craft)
        receipt = telemetry['estimate']['body'].get('accepted')
        if receipt and receipt['frame']['id'] == frame['id']:
            break
        time.sleep(.1)
    else:
        raise AssertionError('Simulator telemetry was not projected')
    propellant_query = {'spacecraftId': craft, 'telemetryVersion': telemetry['estimate']['version'], 'modelVersion': 1}
    call(8103, 'POST', '/api/propellant-estimates', propellant_query, run, 'requester', 403)
    propellant = call(8103, 'POST', '/internal/propellant-estimates', propellant_query, run)
    assert call(8103, 'POST', '/internal/propellant-estimates', propellant_query, run) == propellant
    assert propellant['body']['estimate']['kilograms'] == 1
    assert propellant['body']['source'] == telemetry['estimate']['body']
    forecast = {'id': str(uuid.uuid4()), 'environment': 'SIMULATION', 'issuedAt': tai(now-2),
                'validFor': {'start': tai(now-10), 'end': tai(now+172800)}, 'area': area,
                'cloudFraction': .3, 'provenance': 'synthetic-uniform-cloud-field-local-test'}
    call(8105, 'POST', '/api/weather/simulation-forecasts', forecast, run, 'requester', 403)
    weather = call(8105, 'POST', '/api/weather/simulation-forecasts', forecast, run, 'admin')
    assert call(8105, 'POST', '/api/weather/simulation-forecasts', forecast, run, 'admin') == weather
    query = {'area': area, 'horizon': {'start': tai(now), 'end': tai(now+86400)}}
    assert call(8105, 'POST', '/internal/weather/query', query) == weather
    outside = {**query, 'horizon': {'start': tai(now), 'end': tai(now+172801)}}
    call(8105, 'POST', '/internal/weather/query', outside, expected=404)
    request = call(8101, 'POST', '/api/requests', {'target': 'simulation Daejeon', 'area': area,
                   'priority': 100, 'criteria': {'minimumCoverageFraction': 1, 'maximumCloudFraction': .2}}, run, 'requester')
    request_id = request['id']
    status_path = '/internal/planning/requests/' + request_id
    status = wait_for(status_path, lambda s: s.get('status') == 'WAITING_INPUTS' and s.get('last_attempt_id'))
    attempt_path = '/internal/planning/input-attempts/' + status['last_attempt_id']
    attempt = call(8102, 'GET', attempt_path)
    asset = next(a for a in attempt['assets'] if a['spacecraftId'] == craft)
    assert asset['inputs']['AGILITY']['value'] == second
    assert 'AGILITY' not in asset['missing']
    assert asset['inputs']['WEATHER']['value']['snapshot'] == weather
    assert 'WEATHER' not in asset['missing']
    assert 'CLOUD_CRITERION_NOT_MET' in asset['missing']
    ground = asset['inputs']['GROUND_SCHEDULE']['value']['body']
    assert ground['scope'] == 'LOCAL_ALLOCATIONS_REQUIRE_PROVIDER_CONFIRMATION'
    assert call(8106, 'GET', '/internal/ground-availability/'+ground['id']) == ground
    assert 'GROUND_SCHEDULE' not in asset['missing']
    assert 'DESIGNATED_ORBIT' in asset['missing']
    assert 'PROPELLANT' not in asset['missing']
    assert asset['inputs']['PROPELLANT']['value'] == propellant
    call(8102, 'GET', '/api/planning/input-attempts/'+attempt['id'], user='requester', expected=403)
    assert call(8102, 'GET', '/api/planning/requests/'+request_id, user='requester')['revision'] == 1
    owned = call(8101, 'POST', '/api/requests', {'target': 'private synthetic', 'area': area}, run+'private', 'admin')
    call(8102, 'GET', '/api/planning/requests/'+owned['id'], user='requester', expected=404)
    revised = call(8101, 'PUT', '/api/requests/'+request_id, {'expectedVersion': request['version'],
                   'submission': {'target': 'revised synthetic', 'area': area, 'priority': 100}}, run+'rev', 'requester')
    wait_for(status_path, lambda s: s.get('revision') == 2)
    call(8101, 'POST', '/api/requests/'+request_id+'/cancel', {'expectedVersion': revised['version']}, run+'cancel', 'requester')
    wait_for(status_path, lambda s: s.get('status') in ('INVALIDATED', 'TERMINAL'))
    call(8101, 'POST', '/api/requests/'+owned['id']+'/cancel', {'expectedVersion': owned['version']}, run+'cancel-private', 'admin')
    newer_forecast = {**forecast, 'id': str(uuid.uuid4()), 'issuedAt': tai(now-1), 'cloudFraction': .8}
    newer_weather = call(8105, 'POST', '/api/weather/simulation-forecasts', newer_forecast, run+'new-weather', 'admin')
    assert call(8105, 'POST', '/internal/weather/query', query) == newer_weather
    assert call(8105, 'GET', '/internal/weather/forecasts/'+forecast['id']) == weather
    changed_propellant = call(8104, 'POST', '/api/propellant-models',
        {'expectedVersion': 1, 'model': {**propellant_model, 'telemetryBindingVersion': 2}}, run+'prop-v2', 'admin')
    assert changed_propellant['version'] == 2
    call(8103, 'POST', '/internal/propellant-estimates', propellant_query, run+'old-model', expected=409)
    call(8103, 'POST', '/internal/propellant-estimates', {**propellant_query, 'modelVersion': 2}, run+'unbound-model', expected=400)
    assert call(8103, 'GET', '/internal/propellant-estimates/'+propellant['id']) == propellant['body']
    assert call(8104, 'GET', '/internal/propellant-models/'+craft+'/versions/1') == propellant_configuration
    assert call(8102, 'GET', attempt_path) == attempt
    evidence = {'requestId': request_id, 'spacecraftId': craft, 'agilityVersion': 2,
                'attemptId': attempt['id'], 'weatherSnapshotId': forecast['id'], 'passed': ['API roles', 'idempotency and CAS',
                'immutable model history', 'broker-driven intake', 'pinned agility collection',
                'missing orbit remains waiting', 'owner privacy', 'revision and cancellation',
                'immutable attempt history', 'weather full-horizon coverage', 'weather pinned history',
                'pinned local ground allocation snapshot', 'source-bound propellant estimate',
                'propellant model rotation and immutable history'], 'scope': 'Input collection only; no committed schedule or hardware feasibility'}
    (ROOT/'.local/planning-input-verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
