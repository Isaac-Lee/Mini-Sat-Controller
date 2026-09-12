#!/usr/bin/env python3
"""Owner-API fixtures/assertions for verify-planning-search.py --with-runs."""
import importlib
import copy
import math
import time
import uuid


def seed(call, craft, run, area):
    image = call(8104, 'GET', '/internal/catalog/search-'+run+'/versions/1')
    downlink = copy.deepcopy(image)
    downlink.update(id='downlink-'+run, durationSeconds=20,
                    resources={'powerWatts': 12, 'generatedMegabytes': .25, 'propellantKilograms': 0})
    downlink['activity'].update(id={'value': 'sim-downlink'}, name='SIMULATED_DOWNLINK',
                                exclusiveResources=[{'value': 'radio'}], commandTemplateReference='sim-downlink:1')
    downlink['template'].update(id='sim-downlink', operation='DOWNLINK')
    call(8104, 'POST', '/api/catalog', downlink, run+'downlink', 'admin')
    reference = lambda catalog: {'catalogId': catalog['id'], 'catalogVersion': catalog['version']}
    call(8104, 'POST', '/api/mission-catalog-bindings', {'expectedVersion': 0, 'bindings': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1',
        'roles': [{'role': 'IMAGING', 'reference': reference(image)},
                  {'role': 'DOWNLINK', 'reference': reference(downlink)}],
        'provenance': 'explicit-synthetic-operation-bindings'}}, run+'bindings', 'admin')
    call(8104, 'POST', '/api/operation-resource-profiles', {'expectedVersion': 0, 'profiles': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
        'profiles': [{'operation': 'IMAGE', 'catalog': reference(image), 'expected': image['resources'],
                      'downlinkMegabytesPerSecond': None},
                     {'operation': 'DOWNLINK', 'catalog': reference(downlink), 'expected': downlink['resources'],
                      'downlinkMegabytesPerSecond': 2}],
        'approvalReference': 'explicit-synthetic-operation-resources',
        'provenance': 'constant simulation rate; no ground contact guarantee'}}, run+'operations', 'admin')
    now = int(time.time()) + int(importlib.import_module('verify-public-orbit').ENV['MSC_TIME_OFFSET_SECONDS'])
    tai = lambda seconds: {'seconds': seconds, 'nanos': 0, 'scale': 'TAI'}
    binding = {'spacecraftId': craft, 'version': 1, 'source': 'simulator:'+run,
               'environment': 'SIMULATION', 'maximumAgeSeconds': 3600, 'futureSkewSeconds': 0,
               'approvalReference': 'explicit-synthetic-run-test'}
    call(8109, 'POST', '/api/telemetry-bindings', binding, run, 'admin')
    call(8104, 'POST', '/api/propellant-models', {'expectedVersion': 0, 'model': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
        'telemetryBindingVersion': 1, 'absoluteUncertaintyKg': .1,
        'maximumUnmodeledLossKgPerSecond': .000001, 'maximumPropagationSeconds': 172800,
        'approvalReference': 'explicit-synthetic-run-test'}}, run, 'admin')
    frame = {'id': str(uuid.uuid4()), 'spacecraftId': craft, 'bindingVersion': 1,
             'source': binding['source'], 'sequence': 1, 'observedAt': tai(now),
             'quality': 'GOOD', 'mode': 'NOMINAL', 'batteryWh': 100, 'storageMb': 1,
             'propellantKg': 1, 'provenance': 'explicit-synthetic-run-test'}
    call(8114, 'POST', '/api/simulation/telemetry', frame, run, 'admin')
    call(8110, 'POST', '/api/safety-policies', {
        'spacecraftId': craft, 'version': 1, 'telemetryBindingVersion': 1,
        'minimumBatteryWh': 20, 'maximumStorageMb': 1000, 'minimumPropellantKg': .1,
        'recoveryApprovals': 2, 'approvalValiditySeconds': 60,
        'approvalReference': 'explicit-synthetic-run-test; operator enable remains required'}, run, 'admin')
    call(8105, 'POST', '/api/weather/simulation-forecasts', {
        'id': str(uuid.uuid4()), 'environment': 'SIMULATION', 'issuedAt': tai(now-1),
        'validFor': {'start': tai(now-10), 'end': tai(now+172800)}, 'area': area,
        'cloudFraction': .3, 'provenance': 'explicit synthetic uniform weather'}, run, 'admin')


def verify(call, published, asset, request_id, attempt_id):
    assert published['requestRevision'] == 1 and published['requestId'] == request_id
    assert published['inputAttemptId'] == attempt_id
    assert len(asset['inputs']) == 10
    assert published['sourceHashes'] == {k: v['sha256'] for k, v in asset['inputs'].items()}
    assert published['catalog'] == asset['catalog']
    assert published['simulationModel'] == asset['simulationModel']
    operations = asset['operations']
    assert operations and published['operations'] == operations
    assert operations['bindings']['value']['body']['spacecraftId'] == asset['spacecraftId']
    assert operations['resourceProfiles']['value']['body']['spacecraftId'] == asset['spacecraftId']
    assert set(operations['catalogs']) == {'IMAGING', 'DOWNLINK'}
    assert operations['catalogs']['IMAGING'] == asset['catalog']
    downlink = operations['catalogs']['DOWNLINK']['value']
    assert downlink['template']['operation'] == 'DOWNLINK'
    effect = next(p for p in operations['resourceProfiles']['value']['body']['profiles'] if p['operation'] == 'DOWNLINK')
    assert effect['catalog'] == {'catalogId': downlink['id'], 'catalogVersion': downlink['version']}
    assert effect['expected'] == downlink['resources'] and effect['downlinkMegabytesPerSecond'] == 2
    candidates = published['run']['candidates']
    assert candidates and len(candidates) == len(asset['activityOptions'])
    for candidate, option in zip(candidates, asset['activityOptions']):
        assert candidate['feasibility']['status'] == 'NOT_EVALUATED'
        assert candidate['activity']['window'] == option['window']
        assert candidate['activity']['definitionVersion'] == option['definitionVersion']
    call(8102, 'GET', '/api/planning/runs/'+published['id'], user='requester', expected=403)
    assert call(8102, 'GET', '/api/planning/runs/'+published['id'], user='operator1') == published
    resource_path = '/api/planning/runs/'+published['id']+'/resources'
    resources = call(8102, 'GET', resource_path, user='operator1')
    assert resources['runId'] == published['id'] and resources['inputAttemptId'] == attempt_id
    assert resources['spacecraftId'] == asset['spacecraftId']
    assert resources['scope'] == 'SIMULATION_BATTERY_STORAGE_PROPELLANT_PROPOSAL_ONLY'
    assert resources['scheduleContextStatus'] == 'CURRENT_HEADS_CAPTURED'
    assert resources['supplyAssumption'] == 'ECLIPSE_GENERATION_LOWER_BOUND_THROUGHOUT'
    assert resources['propellantInterpretation'] == 'HORIZON_END_LOWER_BOUND_MINUS_ALL_BURNS'
    assert resources['scheduleHeads'] == []  # Newly created craft: no pre-existing commitments.
    assert len(resources['candidates']) == len(candidates)
    frame = asset['inputs']['SPACECRAFT_STATE']['value']['estimate']['body']['accepted']['frame']
    mission = asset['inputs']['MISSION_DEFINITION']['value']['body']
    model = published['simulationModel']['value']['body']
    mass_model = asset['inputs']['PROPELLANT']['value']['body']['model']
    profile = published['catalog']['value']['resources']
    seconds = lambda a, b: b['seconds']-a['seconds']+(b['nanos']-a['nanos'])/1e9
    for candidate, assessment in zip(candidates, resources['candidates']):
        assert assessment['candidateId'] == candidate['id']['value']
        assert assessment['issues'] == []
        forecast = assessment['forecast']
        horizon = assessment['horizon']
        assert horizon['start'] == frame['observedAt']
        assert horizon['end'] == candidate['activity']['window']['end']
        elapsed = seconds(horizon['start'], horizon['end'])
        duration = seconds(candidate['activity']['window']['start'], horizon['end'])
        # This fixture has zero eclipse generation and no downlink; independently reproduce
        # its terminal reservoirs, including the waiting interval before the proposed activity.
        assert model['eclipseGenerationWatts'] == 0
        battery = frame['batteryWh']-(model['busDrawWatts']*elapsed+profile['powerWatts']*duration)/3600
        stored = frame['storageMb']+profile['generatedMegabytes']
        mass = max(0, frame['propellantKg']-mass_model['absoluteUncertaintyKg']
                   -elapsed*mass_model['maximumUnmodeledLossKgPerSecond'])-profile['propellantKilograms']
        terminal = forecast['trajectory'][-1]
        for field, expected in [('batteryWh', battery), ('storedMb', stored), ('propellantKg', mass)]:
            assert math.isclose(terminal[field], expected, rel_tol=1e-9, abs_tol=1e-8), (field, terminal[field], expected)
        rejected = battery < mission['minimumBatteryWh'] or stored > mission['storageCapacityMb'] or mass < model['minimumPropellantKg']
        assert forecast['status'] == ('REJECTED' if rejected else 'VALIDATED')
    call(8102, 'GET', resource_path, user='requester', expected=403)
    assert call(8102, 'GET', resource_path, user='operator1') == resources
    return resources


if __name__ == '__main__':
    import subprocess
    import sys
    from pathlib import Path
    subprocess.run([sys.executable, str(Path(__file__).with_name('verify-planning-search.py')),
                    '--with-runs', *sys.argv[1:]], check=True)
