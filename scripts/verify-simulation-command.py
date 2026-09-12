#!/usr/bin/env python3
"""Exercise deployed modeled command completion; does not claim ground delivery."""
from decimal import Decimal
import argparse
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--after-restart', action='store_true')
    args = parser.parse_args()
    if args.after_restart:
        baseline = json.loads((api.ROOT / '.local/simulation-command-restart-baseline.json').read_text())
        scenario, ledger = baseline['scenario'], baseline['ledger']
        path = '/internal/simulation/scenarios/' + scenario['id']
        assert call(8114, 'GET', path) == scenario
        assert call(8114, 'GET', path + '/loads/' + ledger['id']) == ledger
        after = call(8114, 'POST', '/api/simulation/scenarios/' + scenario['id'] + '/advance',
                     {'expectedVersion': scenario['version'], 'targetTick': scenario['body']['currentTick'] + 1},
                     'restart-' + str(uuid.uuid4()), 'admin')
        assert after['body']['reservoirs'] == scenario['body']['reservoirs']
        assert call(8114, 'GET', path + '/loads/' + ledger['id']) == ledger
        report = {'scenarioId': scenario['id'], 'checks': ['scenario survives restart',
                  'command ledger survives restart', 'post-restart advance does not repeat effect']}
        (api.ROOT / '.local/simulation-command-restart-verification.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
        return
    prior = json.loads((api.ROOT / '.local/simulation-scenario-verification.json').read_text())
    source = call(8114, 'GET', '/internal/simulation/scenarios/' + prior['scenarioId'])['body']
    mission, correlation = source['mission'], source['correlation']['body']
    run = 'command-verification-' + str(uuid.uuid4())
    scenario_id = str(uuid.uuid4())
    request = {'id': scenario_id, 'spacecraftId': mission['spacecraftId'],
               'correlationVersion': source['correlation']['version'],
               'resourceProfilesVersion': source['resourceProfiles']['version'],
               'initialTick': correlation['tickEpoch'],
               'reservoirs': {'storedMegabytes': 0, 'propellantKilograms': mission['propellantKg']},
               'provenance': 'isolated modeled IMAGE completion test'}
    saved = call(8114, 'POST', '/api/simulation/scenarios', request, run, 'admin')
    profile = next(p for p in source['resourceProfiles']['body']['profiles'] if p['operation'] == 'IMAGE')
    ref = profile['catalog']
    catalog = call(8104, 'GET', f"/internal/catalog/{ref['catalogId']}/versions/{ref['catalogVersion']}")
    parameters = {}
    for name, rule in catalog['template']['parameters'].items():
        if rule['required']:
            parameters[name] = (str(rule['minimum']) if rule['type'] == 'NUMBER' else
                                'false' if rule['type'] == 'BOOLEAN' else
                                next(iter(rule['allowedValues']), 'synthetic-test'))
    frequency = correlation['ticksPerSecond']
    def tai(tick):
        nanos = (correlation['taiEpoch']['seconds'] * 10**9 + correlation['taiEpoch']['nanos']
                 + (tick - correlation['tickEpoch']) * (10**9 // frequency))
        seconds, fraction = divmod(nanos, 10**9)
        return {'seconds': seconds, 'nanos': fraction, 'scale': 'TAI'}
    duration = Decimal(str(catalog['durationSeconds'])) * frequency
    assert duration == duration.to_integral_value() and duration > 0
    start = request['initialTick'] + frequency
    end = start + int(duration)
    command_id, load_id = str(uuid.uuid4()), str(uuid.uuid4())
    load = {'id': {'value': load_id}, 'scheduleKey': {
        'spacecraftId': {'value': mission['spacecraftId']},
        'horizon': {'start': tai(request['initialTick']), 'end': tai(end + frequency)}},
        'scheduleVersion': 1, 'commands': [{'id': {'value': command_id},
        'templateReference': f"{catalog['template']['id']}:{catalog['template']['version']}",
        'parameters': parameters, 'timeTag': {'ticks': start, 'clockPartition': correlation['clockPartition'],
        'correlationId': {'value': correlation['timeCorrelationId']}}}],
        'missionDefinitionVersion': mission['missionDefinitionVersion'],
        'timeCorrelationId': {'value': correlation['timeCorrelationId']},
        'checksum': 'synthetic-artifact-reference', 'authorizationEvidenceReference': 'trace-only',
        'commitDeadline': tai(start)}
    body = {'load': load, 'catalogs': {command_id: ref}}
    path = '/internal/simulation/scenarios/' + scenario_id + '/loads'
    receipt = call(8114, 'POST', path, body, run)
    advance_path = '/api/simulation/scenarios/' + scenario_id + '/advance'
    before = call(8114, 'POST', advance_path, {'expectedVersion': saved['version'], 'targetTick': end - 1},
                  run + '-before', 'admin')
    assert before['body']['reservoirs'] == request['reservoirs']
    advance = {'expectedVersion': before['version'], 'targetTick': end}
    after = call(8114, 'POST', advance_path, advance, run + '-end', 'admin')
    assert call(8114, 'POST', advance_path, advance, run + '-end', 'admin') == after
    assert after['body']['reservoirs']['storedMegabytes'] == catalog['resources']['generatedMegabytes']
    assert after['body']['reservoirs']['propellantKilograms'] == mission['propellantKg'] - catalog['resources']['propellantKilograms']
    assert call(8114, 'POST', path, body, run + '-retransmit') == receipt
    ledger = call(8114, 'GET', path + '/' + load_id)
    assert ledger['body']['entries'][0]['status'] == 'EFFECT_APPLIED'
    later = call(8114, 'POST', advance_path, {'expectedVersion': after['version'], 'targetTick': end + 1},
                 run + '-later', 'admin')
    assert later['body']['reservoirs'] == after['body']['reservoirs']
    (api.ROOT / '.local/simulation-command-restart-baseline.json').write_text(
        json.dumps({'scenario': later, 'ledger': ledger}, indent=2) + '\n')
    report = {'scenarioId': scenario_id, 'loadId': load_id,
              'checks': ['no effect before completion', 'exact declared resource effect',
                         'advance replay', 'original receipt after completion',
                         'persistent applied ledger', 'no duplicate effect on later advance'],
              'scope': 'Declared onboard IMAGE effect only; no battery, payload or ground-delivery proof'}
    (api.ROOT / '.local/simulation-command-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
