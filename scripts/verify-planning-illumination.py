#!/usr/bin/env python3
"""Evaluate an existing synthetic Planning run through deployed owner services."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    fixture = json.loads((api.ROOT / '.local/planning-search-verification.json').read_text())
    run_id = fixture['runId']
    run = call(8102, 'GET', '/api/planning/runs/' + run_id)
    craft = run['spacecraftId']
    assert craft.startswith('000-sim-search-'), 'Only explicit synthetic search fixtures are allowed'
    model = run['simulationModel']['value']['body']
    geometry = run['geometry']['value']
    candidates = run['run']['candidates']
    assert 1 <= len(candidates) <= 32
    instant_key = lambda t: (t['seconds'], t['nanos'])
    horizon = {'start': min((c['activity']['window']['start'] for c in candidates), key=instant_key),
               'end': max((c['activity']['window']['end'] for c in candidates), key=instant_key)}
    area = geometry['area']
    assumptions = {'spacecraftId': craft, 'missionDefinitionVersion': model['missionDefinitionVersion'],
                   'environment': 'SIMULATION', 'solarModel': 'orekit-13.1.8-analytical-solar-position',
                   'referenceDigest': geometry['prediction']['body']['referenceDigest'],
                   'validInterval': horizon,
                   'extent': {'id': area['id'], 'westLongitudeDegrees': area['west'],
                              'eastLongitudeDegrees': area['east'], 'southLatitudeDegrees': area['south'],
                              'northLatitudeDegrees': area['north'],
                              'altitudeMeters': geometry['prediction']['body']['query']['target']['altitudeMeters']},
                   'maximumRateRadiansPerSecond': .001, 'evaluationErrorRadians': .0001,
                   'maximumStepSeconds': 5,
                   'justificationReference': 'synthetic Planning integration assumption; not physical qualification'}
    source_path = '/internal/solar-interval-assumptions/' + craft
    current = call(8104, 'GET', source_path, expected=[200, 404])
    version = current.get('version', 0)
    key = 'planning-illumination-' + str(uuid.uuid4())
    source = call(8104, 'POST', '/api/solar-interval-assumptions',
                  {'expectedVersion': version, 'assumptions': assumptions}, key, 'admin')
    request = {'assumptionsVersion': source['version']}
    path = '/api/planning/runs/' + run_id + '/illumination'
    call(8102, 'POST', path, request, key, 'requester', 403)
    first = call(8102, 'POST', path, request, key, 'operator1')
    body = first['body']
    assert body['runId'] == run_id and body['assumptionsVersion'] == source['version']
    assert len(body['runSha256']) == 64 and len(body['candidates']) == len(candidates)
    for candidate, result in zip(candidates, body['candidates']):
        assert result['candidateId'] == candidate['id']['value']
        owner = result['ownerResult']
        evidence = owner['body']
        assert evidence['assumptions'] == source
        assert evidence['request']['query']['horizon'] == candidate['activity']['window']
        assert evidence['outcome'] in ['SUPPORTED_BY_DECLARED_ASSUMPTIONS', 'NOT_ESTABLISHED']
        assert evidence['interval']['cells']
        assert call(8103, 'GET', '/internal/solar-intervals/' + owner['id']) == evidence
    assert call(8102, 'POST', path, request, key, 'operator1') == first
    assert call(8102, 'POST', path, request, key + '-second', 'operator1') == first
    call(8102, 'POST', path, {'assumptionsVersion': source['version'] + 1}, key, 'operator1', 409)
    read_path = path + '/' + str(source['version'])
    assert call(8102, 'GET', read_path, user='operator1') == first
    assert call(8102, 'GET', read_path.replace('/api/', '/internal/')) == first
    call(8102, 'GET', read_path, user='requester', expected=403)
    assert call(8102, 'GET', '/api/planning/runs/' + run_id) == run
    report = {'runId': run_id, 'assessmentId': first['id'], 'assumptionsVersion': source['version'],
              'outcomes': [r['ownerResult']['body']['outcome'] for r in body['candidates']],
              'checks': ['requester compute/read denied', 'candidate windows and exact owner source retained',
                         'real FD interval calculation and persisted owner read', 'same-key replay',
                         'different-key immutable result reuse', 'changed-request key conflict',
                         'Planning API/internal persisted reads', 'original run and feasibility unchanged'],
              'scope': 'Existing synthetic Planning candidate; test-only assumptions, no schedule commitment'}
    (api.ROOT / '.local/planning-illumination-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
