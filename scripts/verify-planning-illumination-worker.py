#!/usr/bin/env python3
"""Seed and verify automatic illumination for verify-planning-search --with-illumination."""
import time


def seed(call, craft, key, area, epoch):
    reference = call(8103, 'GET', '/internal/reference-context')
    return call(8104, 'POST', '/api/solar-interval-assumptions', {
        'expectedVersion': 0, 'assumptions': {
            'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
            'solarModel': 'orekit-13.1.8-analytical-solar-position', 'referenceDigest': reference['digest'],
            'validInterval': {'start': {**epoch, 'seconds': epoch['seconds'] - 600},
                              'end': {**epoch, 'seconds': epoch['seconds'] + 1800}},
            'extent': {'id': area['id'], 'westLongitudeDegrees': area['west'],
                       'eastLongitudeDegrees': area['east'], 'southLatitudeDegrees': area['south'],
                       'northLatitudeDegrees': area['north'], 'altitudeMeters': 0},
            'maximumRateRadiansPerSecond': .001, 'evaluationErrorRadians': .0001,
            'maximumStepSeconds': 5,
            'justificationReference': 'synthetic automatic worker integration; not physical qualification'}},
        key + '-solar', 'admin')


def verify(call, run, source):
    path = '/api/planning/runs/' + run['id']
    until = time.monotonic() + 60
    while time.monotonic() < until:
        state = call(8102, 'GET', path + '/illumination-work', user='operator1')
        if state['status'] == 'EVALUATED':
            break
        assert state['status'] in ['QUEUED', 'EVALUATING', 'WAITING_INPUTS'], state
        time.sleep(.5)
    else:
        raise AssertionError(('Automatic illumination did not complete', state))
    assert state['assumptions_version'] == source['version']
    assert state['attempts'] >= 1 and state['last_issue'] is None
    call(8102, 'GET', path + '/illumination-work', user='requester', expected=403)
    assert call(8102, 'GET', path.replace('/api/', '/internal/') + '/illumination-work') == state
    saved = call(8102, 'GET', path + '/illumination/' + str(source['version']), user='operator1')
    assert saved['body']['runId'] == run['id']
    assert len(saved['body']['candidates']) == len(run['run']['candidates'])
    for expected, actual in zip(run['run']['candidates'], saved['body']['candidates']):
        assert expected['id']['value'] == actual['candidateId']
        owner = actual['ownerResult']
        assert owner['body']['assumptions'] == source
        assert owner['body']['request']['query']['horizon'] == expected['activity']['window']
        assert owner['body']['interval']['cells']
        assert call(8103, 'GET', '/internal/solar-intervals/' + owner['id']) == owner['body']
    assert call(8102, 'GET', path) == run
    return {'assessmentId': saved['id'], 'work': state,
            'outcomes': [c['ownerResult']['body']['outcome'] for c in saved['body']['candidates']],
            'scope': 'Scheduled worker only; no manual illumination POST, no schedule commitment'}
