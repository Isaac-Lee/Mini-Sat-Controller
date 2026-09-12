"""Continue the V1 selected-schedule scenario through authorized IMAGE execution and evidence."""
import hashlib
import json
import time
import uuid


def execute(call, run, prepared, correlation, key, with_downlink=False):
    craft, load = run['spacecraftId'], prepared['body']['load']
    load_id = prepared['id']
    call(8104, 'POST', '/api/authority-policies', {'expectedVersion': 0, 'policy': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1',
        'rules': [{'context': {'actionClass': 'IMAGE', 'phase': 'ROUTINE',
                               'spacecraftMode': 'NOMINAL', 'risk': 'LOW'},
                   'requirement': 'HUMAN_APPROVAL'}],
        'provenance': 'Explicit V1 simulation review; operator approval required'}}, key, 'admin')
    safety_path = '/api/safety/' + craft
    for operator in ('operator1', 'operator2'):
        safety = call(8110, 'GET', safety_path, user=operator)
        if not safety['body']['frozen']:
            break
        approval = call(8110, 'POST', safety_path + '/recovery-approvals', {
            'expectedSafetyVersion': safety['version'], 'decisionReference': 'V1 synthetic mission enable'},
            key + operator, operator)
        assert approval['approved'], approval
    assert call(8110, 'POST', safety_path + '/check', user='operator1')['clear']
    scenario_id = str(uuid.uuid4())
    scenario = call(8114, 'POST', '/api/simulation/scenarios', {
        'id': scenario_id, 'spacecraftId': craft, 'correlationVersion': correlation['version'],
        'resourceProfilesVersion': 1, 'initialTick': 0,
        'reservoirs': {'storedMegabytes': 1, 'propellantKilograms': 1},
        'provenance': 'V1 request-bound synthetic execution'}, key, 'admin')
    base = '/api/command-loads/' + load_id
    release_body = {'scenarioId': scenario_id, 'checksum': load['checksum'],
                    'reviewReference': 'V1 sampled simulation schedule review'}
    call(8107, 'POST', base + '/simulation-release', release_body, key, 'requester', 403)
    release = call(8107, 'POST', base + '/simulation-release', release_body, key, 'operator1')
    assert release['body']['prepared'] == prepared['body']
    call(8107, 'POST', base + '/simulation-dispatch', user='requester', expected=403)
    delivery = call(8107, 'POST', base + '/simulation-dispatch', user='operator1')
    assert delivery['body']['status'] == 'ACCEPTED', delivery
    assert call(8107, 'POST', base + '/simulation-dispatch', user='operator1') == delivery
    command = load['commands'][0]
    tick = command['timeTag']['ticks'] + round(run['catalog']['value']['durationSeconds'] * correlation['body']['ticksPerSecond'])
    advanced = call(8114, 'POST', '/api/simulation/scenarios/' + scenario_id + '/advance', {
        'expectedVersion': scenario['version'], 'targetTick': tick}, key, 'admin')
    assert advanced['body']['reservoirs']['storedMegabytes'] == 2
    link = '/api/simulation/scenarios/' + scenario_id + '/loads/' + load_id + '/link'
    call(8114, 'POST', link, {'expectedVersion': 0, 'connected': True, 'acknowledgmentLost': True,
        'notBeforeTick': 0, 'provenance': 'V1 lost acknowledgment demonstration'}, key, 'admin')
    receive = '/internal/simulation/scenarios/' + scenario_id + '/loads/' + load_id + '/receive'
    unknown = call(8114, 'POST', receive, {'channel': 'ACKNOWLEDGMENT'}, key + '-ack')
    assert unknown['belief'] == 'UNKNOWN' and unknown['reason'] == 'ACKNOWLEDGMENT_LOST'
    observed = call(8114, 'POST', receive, {'channel': 'RECONCILIATION'}, key + '-observe')
    assert observed['belief'] == 'OBSERVED'
    until = time.monotonic() + 30
    while time.monotonic() < until:
        evidence = call(8107, 'GET', '/api/simulation-execution-evidence/' + scenario_id + '/' + load_id,
                        user='operator1', expected=[200, 404])
        if 'body' in evidence:
            break
        time.sleep(.3)
    else:
        raise AssertionError(('Execution event did not arrive at Control', evidence))
    bound = call(8107, 'POST', base + '/simulation-execution/reconcile', key=key, user='operator1')
    assert bound['body']['status'] == 'SIMULATION_EFFECTS_CONFIRMED'
    assert bound['body']['requestIds'] == [run['requestId']]
    payload_id = hashlib.sha256(json.dumps([scenario_id, command['id']['value']], separators=(',', ':')).encode()).hexdigest()
    until = time.monotonic() + 30
    while time.monotonic() < until:
        payload = call(8114, 'GET', '/internal/simulation/payloads/' + payload_id, expected=[200, 404])
        if 'body' in payload:
            break
        time.sleep(.3)
    else:
        raise AssertionError(('Synthetic payload not produced', payload))
    result = {'scenarioId': scenario_id, 'releasedLoadId': load_id,
            'delivery': delivery['body']['status'], 'lostAckBelief': unknown['belief'],
            'executionStatus': bound['body']['status'], 'requestIds': bound['body']['requestIds'],
            'payloadId': payload_id, 'payloadSha256': payload['body']['sha256']}

    if with_downlink:
        import importlib
        result['downlink'] = importlib.import_module('verify-v1-downlink').execute(call, run, result, prepared, correlation, key)
    return result
