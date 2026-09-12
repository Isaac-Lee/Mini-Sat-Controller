"""Continue the request-bound IMAGE scenario through booked DOWNLINK and owned product bytes."""
import hashlib
import importlib
import time


def wait_read(call, port, path, predicate, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        result = call(port, 'GET', path, expected=[200, 404])
        if predicate(result):
            return result
        time.sleep(.4)
    raise AssertionError((path, result))


def execute(call, run, execution, image_prepared, correlation, key):
    craft, scenario = run['spacecraftId'], execution['scenarioId']
    image_start = image_prepared['body']['load']['scheduleKey']['horizon']['start']
    tai = lambda offset: {**image_start, 'seconds': image_start['seconds'] + offset}
    catalog = run['operations']['catalogs']['DOWNLINK']['value']
    window = {'start': tai(30), 'end': tai(30 + round(catalog['durationSeconds']))}
    solution = run['geometry']['value']['prediction']['body']['solutionId']
    predicted = call(8103, 'POST', '/internal/predictions', {'solutionId': solution,
        'horizon': {'start': tai(40), 'end': tai(41)}, 'stepSeconds': 1}, key + '-station')['body']
    point = call(8103, 'GET', '/internal/predictions/' + predicted['id'] + '/samples')['groundTrack'][0]
    station = {'id': 'v1-' + key, 'version': 1, 'latitudeDegrees': point['latitudeDegrees'],
        'longitudeDegrees': point['longitudeDegrees'], 'altitudeMeters': 0,
        'minimumElevationDegrees': 5, 'downlinkMegabytesPerSecond': 2,
        'approvalReference': 'V1 synthetic station; no physical ground contact'}
    call(8106, 'POST', '/api/stations', station, key, 'admin')
    call(8114, 'POST', '/api/simulation/stations', station, key, 'admin')
    access = call(8103, 'POST', '/internal/access-predictions', {'solutionId': solution, 'query': {
        'kind': 'GROUND_CONTACT', 'target': {'id': station['id'] + ':1',
        'latitudeDegrees': station['latitudeDegrees'], 'longitudeDegrees': station['longitudeDegrees'],
        'altitudeMeters': 0}, 'horizon': {'start': tai(0), 'end': tai(90)},
        'minimumElevationDegrees': 5, 'maximumOffNadirDegrees': 30, 'minimumDurationSeconds': 10}}, key + '-ground')
    pending = call(8106, 'POST', '/api/bookings', {'stationId': station['id'], 'stationVersion': 1,
        'spacecraftId': craft, 'window': window, 'accessPredictionId': access['id'],
        'requestedMegabytes': 1}, key, 'admin')
    booking = wait_read(call, 8106, '/api/bookings/' + pending['id'], lambda x: x.get('status') == 'CONFIRMED')
    decision = call(8102, 'POST', '/api/planning/runs/' + run['id'] + '/simulation-downlink', {
        'bookingId': booking['id'], 'window': window, 'expectedScheduleVersion': 0,
        'reviewReference': 'V1 downlink after selected IMAGE'}, key, 'operator1')
    operation = decision['body']
    assert operation['sourceImageDecision'] == run['requestId'] + ':1'
    assert operation['sourceRunId'] == run['id'] and operation['runId'] != run['id']
    assert operation['proposal']['id']['value'] == operation['runId']
    activity = operation['activityId']
    schedule = operation['schedule']
    deadline = tai(20)
    prepared = call(8107, 'POST', '/api/command-loads/prepare', {
        'id': {'value': 'v1-downlink-' + key}, 'schedule': schedule['key'], 'scheduleVersion': schedule['version'],
        'correlationVersion': correlation['version'],
        'catalogsByActivity': {activity: {'catalogId': catalog['id'], 'catalogVersion': catalog['version']}},
        'parametersByActivity': {activity: {}}, 'deadline': deadline}, key + '-downlink', 'operator1')
    load, load_id = prepared['body']['load'], prepared['id']
    base = '/api/command-loads/' + load_id
    call(8107, 'POST', base + '/approvals', {'checksum': load['checksum'], 'validUntil': deadline,
        'expectedVersion': 0}, key, 'operator1')
    policy = call(8104, 'GET', '/internal/authority-policies/' + craft)
    body = policy['body']
    body['rules'].append({'context': {'actionClass': 'DOWNLINK', 'phase': 'ROUTINE',
        'spacecraftMode': 'NOMINAL', 'risk': 'LOW'}, 'requirement': 'HUMAN_APPROVAL'})
    call(8104, 'POST', '/api/authority-policies', {'expectedVersion': policy['version'], 'policy': body}, key + '-downlink', 'admin')
    call(8107, 'POST', base + '/simulation-release', {'scenarioId': scenario,
        'checksum': load['checksum'], 'reviewReference': 'V1 booked downlink'}, key + '-downlink', 'operator1')
    assert call(8107, 'POST', base + '/simulation-dispatch', user='operator1')['body']['status'] == 'ACCEPTED'
    command = load['commands'][0]
    plan = call(8114, 'POST', '/internal/simulation/downlinks', {'scenarioId': scenario, 'loadId': load_id,
        'commandId': command['id']['value'], 'payloadId': execution['payloadId'], 'bookingId': booking['id']}, key)
    manifest = call(8111, 'POST', '/api/acquisition/simulation-manifests', {'scenarioId': scenario,
        'planIds': [plan['id']]}, key)
    assert manifest['body']['completeness'] == 'INCOMPLETE'
    link_path = '/api/simulation/scenarios/' + scenario + '/loads/' + load_id + '/link'
    receive = '/internal/simulation/downlinks/' + plan['id'] + '/receive'
    call(8114, 'POST', link_path, {'expectedVersion': 0, 'connected': True, 'acknowledgmentLost': False,
        'notBeforeTick': 0, 'provenance': 'V1 reception'}, key, 'admin')
    assert call(8114, 'POST', receive, {'channel': 'RECONCILIATION'}, key + '-pending')['belief'] == 'UNKNOWN'
    current = call(8114, 'GET', '/internal/simulation/scenarios/' + scenario)
    completion = command['timeTag']['ticks'] + round(catalog['durationSeconds'] * correlation['body']['ticksPerSecond'])
    call(8114, 'POST', '/api/simulation/scenarios/' + scenario + '/advance',
        {'expectedVersion': current['version'], 'targetTick': completion}, key + '-downlink', 'admin')
    call(8114, 'POST', link_path, {'expectedVersion': 1, 'connected': False, 'acknowledgmentLost': False,
        'notBeforeTick': 0, 'provenance': 'V1 disconnected reception'}, key + '-disconnect', 'admin')
    assert call(8114, 'POST', receive, {'channel': 'RECONCILIATION'}, key + '-disconnected')['belief'] == 'UNKNOWN'
    call(8114, 'POST', link_path, {'expectedVersion': 2, 'connected': True, 'acknowledgmentLost': False,
        'notBeforeTick': 0, 'provenance': 'V1 reconciled reception'}, key + '-connect', 'admin')
    received = call(8114, 'POST', receive, {'channel': 'RECONCILIATION'}, key + '-received')
    assert received['belief'] == 'OBSERVED' and received['receipt']['sha256'] == execution['payloadSha256']
    call(8114, 'POST', '/internal/simulation/scenarios/' + scenario + '/loads/' + load_id + '/receive',
        {'channel': 'RECONCILIATION'}, key + '-effects')
    wait_read(call, 8107, '/api/simulation-execution-evidence/' + scenario + '/' + load_id, lambda x: 'body' in x)
    bound = call(8107, 'POST', base + '/simulation-execution/reconcile', key=key + '-downlink', user='operator1')
    assert bound['body']['requestIds'] == [run['requestId']]
    completed = wait_read(call, 8111, '/api/acquisition/simulation-manifests/' + manifest['id'],
                          lambda x: x.get('body', {}).get('completeness') == 'COMPLETE')
    product = wait_read(call, 8112, '/api/products/simulation-source-packages/' + manifest['id'], lambda x: 'body' in x)
    assert product['body']['acquisitionManifest'] == completed
    source = product['body']['sources'][0]
    raw = importlib.import_module('verify-simulation-product').read_owned(source['objectReference'])
    assert len(raw) == 1_000_000 and hashlib.sha256(raw).hexdigest() == execution['payloadSha256']
    preview = call(8112, 'POST', '/api/products/simulation-source-packages/' + product['id'] + '/sources/' + plan['id'] + '/preview', {}, key)
    return {'operationId': operation['activityId'], 'bookingId': booking['id'], 'loadId': load_id,
        'downlinkPlanId': plan['id'], 'manifestId': manifest['id'], 'productId': product['id'],
        'sourceBytes': len(raw), 'sourceSha256': source['sha256'], 'preview': preview,
        'requestId': run['requestId'], 'scope': 'Request-bound synthetic image and ground reception; fulfillment binding pending'}
