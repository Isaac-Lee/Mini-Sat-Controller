#!/usr/bin/env python3
"""Verify deployed current-schedule resource reassessment of an immutable Planning run."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    fixture = json.loads((api.ROOT / '.local/planning-search-verification.json').read_text())
    run_id = fixture['runId']
    base = '/api/planning/runs/' + run_id
    run = api.call(8102, 'GET', base)
    assert run['spacecraftId'].startswith('000-sim-search-')
    original = api.call(8102, 'GET', base + '/resources')
    path = base + '/resources/reassess'
    key = 'resource-reassessment-' + str(uuid.uuid4())
    api.call(8102, 'POST', path, key=key, user='requester', expected=403)
    first = api.call(8102, 'POST', path, key=key, user='operator1')
    body = first['body']
    assert body['runId'] == run_id and len(body['runSha256']) == 64
    assert body['evaluatedAt']['scale'] == 'TAI'
    resources = body['resources']
    assert resources['runId'] == run_id and resources['spacecraftId'] == run['spacecraftId']
    assert resources['scope'] == 'SIMULATION_BATTERY_STORAGE_PROPELLANT_PROPOSAL_ONLY'
    assert resources['scheduleContextStatus'] == 'CURRENT_HEADS_CAPTURED'
    assert len(resources['candidates']) == len(run['run']['candidates'])
    now = (body['evaluatedAt']['seconds'], body['evaluatedAt']['nanos'])
    for candidate, result in zip(run['run']['candidates'], resources['candidates']):
        assert candidate['id']['value'] == result['candidateId']
        start = candidate['activity']['window']['start']
        if (start['seconds'], start['nanos']) < now:
            assert 'CANDIDATE_START_PRECEDES_EVALUATION' in result['issues']
            assert not result.get('forecast')
    assert api.call(8102, 'POST', path, key=key, user='operator1') == first
    read_path = '/api/planning/resource-reassessments/' + first['id']
    assert api.call(8102, 'GET', read_path, user='operator1') == first
    assert api.call(8102, 'GET', read_path.replace('/api/', '/internal/')) == first
    api.call(8102, 'GET', read_path, user='requester', expected=403)
    second = api.call(8102, 'POST', path, key=key + '-fresh', user='operator1')
    assert second['id'] != first['id'] and second['body']['runSha256'] == body['runSha256']
    assert api.call(8102, 'GET', read_path) == first
    api.call(8102, 'POST', '/api/planning/runs/not-the-original/resources/reassess',
             key=key, user='operator1', expected=409)
    assert api.call(8102, 'GET', base) == run
    assert api.call(8102, 'GET', base + '/resources') == original
    evidence = {'runId': run_id, 'reassessmentId': first['id'], 'evaluatedAt': body['evaluatedAt'],
                'scheduleHeadCount': len(resources['scheduleHeads']),
                'candidateIssues': [c['issues'] for c in resources['candidates']],
                'checks': ['current-time/current-head resource capture', 'role denial',
                           'immutable replay and historical read', 'new key creates new assessment',
                           'same key cannot be rebound to another run', 'original run and assessment preserved'],
                'scope': 'Resource evidence only; no schedule reservation, commit or release'}
    (api.ROOT / '.local/resource-reassessment-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
