#!/usr/bin/env python3
"""V1 selected candidate -> persistent schedule -> Tasking progress -> Control preparation."""
import time


def verify(call, run, key):
    candidate = run['run']['candidates'][0]
    body = {'candidateId': candidate['id']['value'], 'cameraModelVersion': 1,
            'expectedScheduleVersion': 0, 'reviewReference': 'V1-sampled-simulation-review'}
    path = '/api/planning/runs/' + run['id'] + '/simulation-commit'
    call(8102, 'POST', path, body, key, 'requester', 403)
    committed = call(8102, 'POST', path, body, key, 'operator1')
    assert call(8102, 'POST', path, body, key, 'operator1') == committed
    decision = committed['body']
    assert decision['environment'] == 'SIMULATION'
    assert decision['evaluationModel'] == 'SIMULATION_V1_SAMPLED_REVIEW'
    schedule = decision['schedule']
    assert schedule['assignments'][0]['requestId']['value'] == run['requestId']
    assert schedule['assignments'][0]['candidateId'] == candidate['id']
    assert call(8102, 'POST', '/internal/planning/schedules/query',
                {'key': schedule['key'], 'version': schedule['version']}, key) == schedule
    until = time.monotonic() + 30
    while time.monotonic() < until:
        request = call(8101, 'GET', '/api/requests/' + run['requestId'], user='requester')
        if request['body']['request']['status'] == 'SCHEDULED':
            break
        time.sleep(.3)
    else:
        raise AssertionError(('Tasking did not receive schedule progress', request))
    start = candidate['activity']['window']['start']
    epoch = {**start, 'seconds': start['seconds'] - 30}
    end = {**start, 'seconds': start['seconds'] + 3600}
    correlation = call(8104, 'POST', '/api/simulation-time-correlations', {
        'expectedVersion': 0, 'correlation': {
            'spacecraftId': run['spacecraftId'], 'missionDefinitionVersion': 'sim-v1',
            'timeCorrelationId': 'simulation-time', 'clockPartition': 'sim-v1',
            'taiEpoch': epoch, 'tickEpoch': 0, 'ticksPerSecond': 10,
            'validInterval': {'start': epoch, 'end': end}, 'environment': 'SIMULATION',
            'approvalReference': 'V1-review', 'provenance': 'Synthetic clock; no hardware claims'}},
        key, 'admin')
    activity = candidate['activity']['id']['value']
    catalog = run['catalog']['value']
    prepared = call(8107, 'POST', '/api/command-loads/prepare', {
        'id': {'value': 'v1-' + key}, 'schedule': schedule['key'],
        'scheduleVersion': schedule['version'], 'correlationVersion': correlation['version'],
        'catalogsByActivity': {activity: {'catalogId': catalog['id'], 'catalogVersion': catalog['version']}},
        'parametersByActivity': {activity: {}}, 'deadline': epoch}, key, 'operator1')
    load = prepared['body']['load']
    assert load['scheduleKey'] == schedule['key']
    assert load['scheduleVersion'] == schedule['version']
    assert len(load['commands']) == 1
    assert prepared['body']['sources']['schedule'] == schedule
    approval = call(8107, 'POST', '/api/command-loads/' + prepared['id'] + '/approvals', {
        'checksum': load['checksum'], 'validUntil': epoch, 'expectedVersion': 0}, key, 'operator1')
    assert approval['body']['actorId'] == 'operator1'
    schedule_check = call(8107, 'POST', '/api/command-loads/' + prepared['id'] + '/schedule-check',
                          user='operator1')
    assert schedule_check['reasons'] == []
    assert call(8102, 'GET', '/internal/planning/runs/' + run['id']) == run
    return {'decisionId': committed['id'], 'schedule': schedule['key'],
            'scheduleVersion': schedule['version'], 'requestStatus': 'SCHEDULED',
            'preparedLoadId': prepared['id'], 'approvalActor': 'operator1', 'environment': 'SIMULATION',
            'deferredChecks': decision['deferredChecks']}
