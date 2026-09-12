#!/usr/bin/env python3
"""Automatic Planning -> Flight Dynamics point access, using explicit synthetic orbit data."""
import argparse
import datetime
import importlib
import json
import math
from pathlib import Path
import time
import uuid

call = importlib.import_module('verify-public-orbit').call
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-only', action='store_true', help='Verify the published model API without starting a planning request')
    parser.add_argument('--timeout-seconds', type=float, default=120,
                        help='Bounded wait for rotated fleet retries, including cache bucket renewal (default: 120)')
    parser.add_argument('--with-runs', action='store_true', help='Verify durable runs with complete simulation input categories')
    parser.add_argument('--with-illumination', action='store_true', help='Verify automatic illumination (requires --with-runs)')
    parser.add_argument('--with-camera', action='store_true', help='Verify automatic sampled camera evaluation (requires --with-runs)')
    parser.add_argument('--with-simulation-commit', action='store_true', help='Verify V1 schedule commitment and Control preparation (requires --with-camera)')
    parser.add_argument('--with-simulation-dispatch', action='store_true', help='Execute and reconcile the prepared V1 simulation load (requires --with-simulation-commit)')
    parser.add_argument('--with-v1-downlink', action='store_true', help='Continue request-bound execution through ground downlink and synthetic product (requires --with-simulation-dispatch)')
    parser.add_argument('--with-v1-result', action='store_true', help='Complete the request and verify requester downloads (requires --with-v1-downlink)')
    args = parser.parse_args()
    if args.with_v1_result and not args.with_v1_downlink:
        parser.error('--with-v1-result requires --with-v1-downlink')
    if args.with_v1_downlink and not args.with_simulation_dispatch:
        parser.error('--with-v1-downlink requires --with-simulation-dispatch')
    if args.with_simulation_dispatch and not args.with_simulation_commit:
        parser.error('--with-simulation-dispatch requires --with-simulation-commit')
    if args.with_simulation_commit and not args.with_camera:
        parser.error('--with-simulation-commit requires --with-camera')
    if args.with_camera and not args.with_runs:
        parser.error('--with-camera requires --with-runs')
    if args.with_illumination and not args.with_runs:
        parser.error('--with-illumination requires --with-runs')
    if not 1 <= args.timeout_seconds <= 900:
        parser.error('--timeout-seconds must be between 1 and 900')
    run = uuid.uuid4().hex
    craft = '000-sim-search-' + run
    catalog = {'id': 'search-' + run, 'version': 1,
               'activity': {'id': {'value': 'sim-imaging'}, 'version': 1, 'name': 'IMAGING_STRIP',
                            'approved': True, 'exclusiveResources': [{'value': 'payload'}],
                            'allowedPhases': ['ROUTINE'], 'allowedModes': ['NOMINAL'],
                            'riskClass': 'LOW', 'commandTemplateReference': 'sim-imaging:1'},
               'template': {'id': 'sim-imaging', 'version': 1, 'operation': 'IMAGE', 'parameters': {}},
               'resources': {'powerWatts': 20, 'generatedMegabytes': 1, 'propellantKilograms': 0},
               'authority': 'AUTO_ALLOWED', 'durationSeconds': 10,
               'approvalReference': 'explicit-synthetic-search-test'}
    call(8104, 'POST', '/api/catalog', catalog, run, 'admin')
    call(8104, 'POST', '/api/missions', {
        'spacecraftId': craft, 'catalogId': catalog['id'], 'catalogVersion': 1,
        'missionDefinitionVersion': 'sim-v1', 'batteryCapacityWh': 100, 'minimumBatteryWh': 20,
        'storageCapacityMb': 1000, 'propellantKg': 1, 'rechargeWatts': 0,
        'timeCorrelationId': 'simulation-time', 'provenance': 'synthetic-not-SPACEEYE-hardware'}, run, 'admin')
    call(8104, 'POST', '/api/agility-models', {'expectedVersion': 0, 'model': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
        'maximumOffNadirDegrees': 30, 'slewRateDegreesPerSecond': 1, 'settlingSeconds': 5,
        'approvalReference': 'explicit-synthetic-search-test'}}, run, 'admin')
    model = {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
        'phase': 'ROUTINE', 'mode': 'NOMINAL', 'swathWidthMeters': 1000,
        'groundSampleDistanceMeters': 10, 'maximumOffNadirDegrees': 30,
        'minimumSunElevationDegrees': 10, 'requiresWeatherEvaluation': True,
        'busDrawWatts': 5, 'sunlitGenerationWatts': 10, 'eclipseGenerationWatts': 0,
        'worstCaseSunlitFraction': 0, 'minimumPropellantKg': .1,
        'approvalReference': 'explicit-synthetic-search-test'}
    publish = {'expectedVersion': 0, 'model': model}
    base = '/api/simulation-planning-models'
    call(8104, 'POST', base, publish, run, 'operator1', 403)
    first_model = call(8104, 'POST', base, publish, run, 'admin')
    assert call(8104, 'POST', base, publish, run, 'admin') == first_model
    call(8104, 'POST', base, publish, run+'stale', 'admin', 409)
    call(8104, 'GET', base+'/'+craft, user='requester', expected=403)
    second_model = call(8104, 'POST', base, {
        'expectedVersion': 1, 'model': {**model, 'busDrawWatts': 6}}, run+'v2', 'admin')
    assert second_model['version'] == 2
    assert call(8104, 'GET', base+'/'+craft+'/versions/1', user='admin') == first_model
    call(8104, 'POST', base, {
        'expectedVersion': 2, 'model': {**model, 'missionDefinitionVersion': 'wrong'}},
        run+'wrong-mission', 'admin', 400)
    if args.model_only:
        result = {'spacecraftId': craft, 'modelVersion': second_model['version'],
                  'passed': ['simulation model roles', 'idempotent publication',
                             'model CAS', 'immutable model history', 'mission version binding']}
        (ROOT / '.local/simulation-planning-model-verification.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result, indent=2))
        return
    utc = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=300)).strftime('%Y-%m-%dT%H:%M:%S')
    epoch = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': utc})
    call(8103, 'POST', '/internal/orbits', {
        'solutionId': run, 'spacecraftId': craft, 'epoch': epoch,
        'positionMeters': {'x': 7000000, 'y': 0, 'z': 0},
        'velocityMetersPerSecond': {'x': 0, 'y': math.sqrt(3.986004418e14 / 7000000), 'z': 0},
        'provenance': 'Synthetic planning search fixture'}, run)
    prediction = call(8103, 'POST', '/internal/predictions', {
        'solutionId': run, 'horizon': {'start': epoch, 'end': {**epoch, 'seconds': epoch['seconds'] + 1}},
        'stepSeconds': 1}, run)['body']
    point = call(8103, 'GET', '/internal/predictions/' + prediction['id'] + '/samples')['groundTrack'][0]
    call(8103, 'POST', '/api/orbit-designations/' + craft, {
        'solutionId': run, 'expectedVersion': 0, 'decisionReference': 'explicit-synthetic-search-test'}, run, 'admin')
    lat, lon = point['latitudeDegrees'], point['longitudeDegrees']
    area = {'id': 'search-area', 'west': lon-.001, 'east': lon+.001,
            'south': lat-.001, 'north': lat+.001, 'sourceReference': 'synthetic-propagated-subpoint'}
    if args.with_runs:
        importlib.import_module('verify-planning-runs').seed(call, craft, run, area)
    camera_source = None
    if args.with_camera:
        camera_source = importlib.import_module('verify-planning-camera-worker').seed(call, craft, run)
    solar_source = None
    if args.with_illumination:
        solar_source = importlib.import_module('verify-planning-illumination-worker').seed(call, craft, run, area, epoch)
    request = call(8101, 'POST', '/api/requests', {
        'target': 'Synthetic orbital subpoint',
        'area': area,
        'criteria': {'minimumCoverageFraction': 1, 'maximumCloudFraction': 1},
        'deadline': {**epoch, 'seconds': epoch['seconds'] + 900}, 'priority': 100,
        'preference': 'AUTO'}, run, 'requester')
    request_id = request['id']
    started = time.monotonic()
    published_run = None
    published_resources = None
    try:
        until = started + args.timeout_seconds
        while time.monotonic() < until:
            status = call(8102, 'GET', '/internal/planning/requests/' + request_id, expected=[200, 404])
            attempt_id = status.get('last_attempt_id')
            if attempt_id:
                attempt = call(8102, 'GET', '/internal/planning/input-attempts/' + attempt_id)
                asset = next((a for a in attempt['assets'] if a['spacecraftId'] == craft), None)
                if asset and asset.get('pointGeometry'):
                    if not args.with_runs:
                        break
                    index = call(8102, 'GET', '/internal/planning/input-attempts/'+attempt_id+'/runs')
                    for run_id in index['runIds']:
                        candidate_run = call(8102, 'GET', '/internal/planning/runs/'+run_id)
                        if candidate_run['spacecraftId'] == craft:
                            published_run = candidate_run
                            break
                    if published_run:
                        break
            time.sleep(.5)
        else:
            raise AssertionError(('Automatic point search did not produce evidence', status))
        geometry = asset['pointGeometry']['value']
        assert geometry['scope'] == 'POINT_GEOMETRY_ONLY'
        assert geometry['feasibility'] == 'NOT_EVALUATED'
        saved = geometry['prediction']
        actual = call(8103, 'GET', '/internal/access-predictions/' + saved['id'])
        assert actual == saved['body']
        assert actual['spacecraftId'] == craft and actual['solutionId'] == run
        assert actual['windows'], 'Known future subpoint should have a geometric access window'
        assert asset['catalog']['value'] == catalog
        assert asset['simulationModel']['value'] == second_model
        assert asset['activityOptions'], 'Expected future activity-sized options'
        for option in asset['activityOptions']:
            assert option['feasibility'] == 'NOT_EVALUATED'
            assert option['phase'] == 'ROUTINE' and option['mode'] == 'NOMINAL'
            assert option['window']['end']['seconds'] - option['window']['start']['seconds'] == 10
            assert 'RESOURCE_TIMELINE' in option['remainingGates']
        assert attempt['revision'] == 1 and attempt['requestId'] == request_id
        assert attempt['status'] == 'WAITING_INPUTS'
        result = {'requestId': request_id, 'attemptId': attempt_id, 'predictionId': saved['id'],
                  'elapsedSeconds': round(time.monotonic() - started, 3),
                  'timeoutSeconds': args.timeout_seconds,
                  'passed': ['automatic owner HTTP search', 'immutable prediction binding',
                             'known synthetic access window', 'catalog retained without safety service success',
                             'incomplete feasibility stays unevaluated',
                             'simulation model roles and idempotency', 'model CAS and immutable history',
                             'model mission binding', 'pinned simulation model and activity-sized options']}
        if args.with_runs:
            published_resources = importlib.import_module('verify-planning-runs').verify(call, published_run, asset, request_id, attempt_id)
            result['runId'] = published_run['id']
            result['resourceAssessmentRunId'] = published_resources['runId']
            result['passed'] += ['durable run binds request revision and ten sources',
                                 'pinned candidates retain unevaluated gates', 'run API roles',
                                 'immutable resource assessment and API roles',
                                 'telemetry-anchored reservoir arithmetic and resource decision',
                                 'exact mission operation catalog and resource profile evidence']
        if args.with_illumination:
            result['automaticIllumination'] = importlib.import_module('verify-planning-illumination-worker').verify(call, published_run, solar_source)
            result['passed'] += ['automatic queued illumination with pinned owner version', 'automatic work API roles', 'persisted FD evidence without manual evaluation']
        if args.with_camera:
            result['automaticCamera'] = importlib.import_module('verify-planning-camera-worker').verify(call, published_run, camera_source)
            result['passed'] += ['automatic camera work without manual evaluation', 'candidate AOI and exact model version binding']
        if args.with_simulation_commit:
            result['simulationSchedule'] = importlib.import_module('verify-simulation-schedule').verify(call, published_run, run, args.with_simulation_dispatch, args.with_v1_downlink)
            result['passed'] += ['V1 selected schedule persists with request binding', 'Tasking scheduled progress', 'Control prepares committed schedule']
        if args.with_v1_result:
            result['requestResult'] = importlib.import_module('verify-v1-result').execute(
                call, request_id, result['simulationSchedule']['execution'], run)
            (ROOT / '.local/v1-functional-verification.json').write_text(json.dumps(result, indent=2)+'\n')
        (ROOT / '.local/planning-search-verification.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps(result, indent=2))
    finally:
        current = call(8101, 'GET', '/api/requests/' + request_id, user='requester')
        if current['body']['request']['status'] not in ['FULFILLED', 'CANCELLED', 'EXPIRED', 'REJECTED']:
            call(8101, 'POST', '/api/requests/' + request_id + '/cancel', {'expectedVersion': current['version']}, run+'cancel', 'requester')
        if published_run:
            assert call(8102, 'GET', '/internal/planning/runs/'+published_run['id']) == published_run
        if published_resources:
            assert call(8102, 'GET', '/internal/planning/runs/'+published_run['id']+'/resources') == published_resources


if __name__ == '__main__':
    main()
