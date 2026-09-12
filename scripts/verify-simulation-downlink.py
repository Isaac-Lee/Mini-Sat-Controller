#!/usr/bin/env python3
"""Real owner APIs: IMAGE, S3 payload, Orekit-backed station booking, DOWNLINK reception."""
import datetime
import hashlib
import importlib
import json
import math
import time
import uuid

api = importlib.import_module('verify-public-orbit')
call = api.call


def wait_read(port, path, predicate, timeout=60):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = call(port, 'GET', path, expected=[200, 404])
        if predicate(value):
            return value
        time.sleep(.5)
    raise AssertionError((path, value))


def main():
    run = uuid.uuid4().hex
    craft, clock = 'sim-downlink-' + run, 'clock-' + run
    catalogs = []
    for operation in ['IMAGE', 'DOWNLINK']:
        catalog = {'id': operation.lower() + '-' + run, 'version': 1,
                   'activity': {'id': {'value': operation.lower()}, 'version': 1, 'name': operation,
                                'approved': True, 'exclusiveResources': [{'value': 'bus'}],
                                'allowedPhases': ['ROUTINE'], 'allowedModes': ['NOMINAL'],
                                'riskClass': 'LOW', 'commandTemplateReference': operation + ':1'},
                   'template': {'id': operation, 'version': 1, 'operation': operation, 'parameters': {}},
                   'resources': {'powerWatts': 10, 'generatedMegabytes': 1 if operation == 'IMAGE' else 0, 'propellantKilograms': 0},
                   'authority': 'AUTO_ALLOWED', 'durationSeconds': 10,
                   'approvalReference': 'synthetic downlink integration fixture'}
        call(8104, 'POST', '/api/catalog', catalog, run + operation, 'admin')
        catalogs.append(catalog)
    call(8104, 'POST', '/api/missions', {'spacecraftId': craft, 'catalogId': catalogs[0]['id'], 'catalogVersion': 1,
         'missionDefinitionVersion': 'sim-v1', 'batteryCapacityWh': 100, 'minimumBatteryWh': 10,
         'storageCapacityMb': 10, 'propellantKg': 1, 'rechargeWatts': 0, 'timeCorrelationId': clock,
         'provenance': 'synthetic downlink fixture; not SPACEEYE hardware'}, run, 'admin')
    utc = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=300)).strftime('%Y-%m-%dT%H:%M:%S')
    epoch = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': utc})
    tai = lambda seconds: {**epoch, 'seconds': epoch['seconds'] + seconds}
    call(8104, 'POST', '/api/simulation-time-correlations', {'expectedVersion': 0, 'correlation': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'timeCorrelationId': clock, 'clockPartition': 'sim',
        'taiEpoch': epoch, 'tickEpoch': 0, 'ticksPerSecond': 10, 'validInterval': {'start': epoch, 'end': tai(600)},
        'environment': 'SIMULATION', 'approvalReference': 'synthetic', 'provenance': 'synthetic'}}, run, 'admin')
    ref = lambda c: {'catalogId': c['id'], 'catalogVersion': 1}
    call(8104, 'POST', '/api/operation-resource-profiles', {'expectedVersion': 0, 'profiles': {
        'spacecraftId': craft, 'missionDefinitionVersion': 'sim-v1', 'environment': 'SIMULATION',
        'profiles': [{'operation': c['template']['operation'], 'catalog': ref(c), 'expected': c['resources'],
                      'downlinkMegabytesPerSecond': 1 if c['template']['operation'] == 'DOWNLINK' else None} for c in catalogs],
        'approvalReference': 'synthetic', 'provenance': 'synthetic'}}, run, 'admin')
    call(8103, 'POST', '/internal/orbits', {'solutionId': run, 'spacecraftId': craft, 'epoch': epoch,
         'positionMeters': {'x': 7000000, 'y': 0, 'z': 0},
         'velocityMetersPerSecond': {'x': 0, 'y': math.sqrt(3.986004418e14 / 7000000), 'z': 0}, 'provenance': 'synthetic'}, run)
    prediction = call(8103, 'POST', '/internal/predictions', {'solutionId': run,
        'horizon': {'start': tai(25), 'end': tai(26)}, 'stepSeconds': 1}, run)['body']
    point = call(8103, 'GET', '/internal/predictions/' + prediction['id'] + '/samples')['groundTrack'][0]
    station = {'id': 'station-' + run, 'version': 1, 'latitudeDegrees': point['latitudeDegrees'],
               'longitudeDegrees': point['longitudeDegrees'], 'altitudeMeters': 0, 'minimumElevationDegrees': 5,
               'downlinkMegabytesPerSecond': 1, 'approvalReference': 'synthetic'}
    call(8106, 'POST', '/api/stations', station, run, 'admin')
    call(8114, 'POST', '/api/simulation/stations', station, run, 'admin')
    access = call(8103, 'POST', '/internal/access-predictions', {'solutionId': run, 'query': {
        'kind': 'GROUND_CONTACT', 'target': {'id': station['id'] + ':1', 'latitudeDegrees': station['latitudeDegrees'],
        'longitudeDegrees': station['longitudeDegrees'], 'altitudeMeters': 0}, 'horizon': {'start': epoch, 'end': tai(100)},
        'minimumElevationDegrees': 5, 'maximumOffNadirDegrees': 30, 'minimumDurationSeconds': 10}}, run)
    reservation = {'stationId': station['id'], 'stationVersion': 1, 'spacecraftId': craft,
                   'window': {'start': tai(20), 'end': tai(30)}, 'accessPredictionId': access['id'], 'requestedMegabytes': 1}
    pending = call(8106, 'POST', '/api/bookings', reservation, run, 'admin')
    booking = wait_read(8106, '/api/bookings/' + pending['id'], lambda b: b.get('status') == 'CONFIRMED')
    scenario_id, load_id = str(uuid.uuid4()), str(uuid.uuid4())
    scenario = call(8114, 'POST', '/api/simulation/scenarios', {'id': scenario_id, 'spacecraftId': craft,
        'correlationVersion': 1, 'resourceProfilesVersion': 1, 'initialTick': 0,
        'reservoirs': {'storedMegabytes': 0, 'propellantKilograms': 1}, 'provenance': 'synthetic'}, run, 'admin')
    command_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
    commands = [{'id': {'value': cid}, 'templateReference': c['template']['id'] + ':1', 'parameters': {},
                 'timeTag': {'ticks': tick, 'clockPartition': 'sim', 'correlationId': {'value': clock}}}
                for cid, c, tick in zip(command_ids, catalogs, [10, 200])]
    load = {'id': {'value': load_id}, 'scheduleKey': {'spacecraftId': {'value': craft}, 'horizon': {'start': epoch, 'end': tai(60)}},
            'scheduleVersion': 1, 'commands': commands, 'missionDefinitionVersion': 'sim-v1',
            'timeCorrelationId': {'value': clock}, 'checksum': 'synthetic', 'authorizationEvidenceReference': 'not-a-control-release', 'commitDeadline': tai(1)}
    base = '/internal/simulation/scenarios/' + scenario_id + '/loads/' + load_id
    call(8114, 'POST', '/internal/simulation/scenarios/' + scenario_id + '/loads',
         {'load': load, 'catalogs': {cid: ref(c) for cid, c in zip(command_ids, catalogs)}}, run)
    advance_path = '/api/simulation/scenarios/' + scenario_id + '/advance'
    imaged = call(8114, 'POST', advance_path, {'expectedVersion': scenario['version'], 'targetTick': 110}, run + '-image', 'admin')
    payload_id = hashlib.sha256(json.dumps([scenario_id, command_ids[0]], separators=(',', ':')).encode()).hexdigest()
    payload = wait_read(8114, '/internal/simulation/payloads/' + payload_id, lambda p: 'body' in p)
    binding = {'scenarioId': scenario_id, 'loadId': load_id, 'commandId': command_ids[1], 'payloadId': payload_id, 'bookingId': booking['id']}
    call(8114, 'POST', '/internal/simulation/downlinks', binding, run, 'requester', 403)
    plan = call(8114, 'POST', '/internal/simulation/downlinks', binding, run)
    receive = '/internal/simulation/downlinks/' + plan['id'] + '/receive'
    channel = {'channel': 'RECONCILIATION'}
    assert call(8114, 'POST', receive, channel, run + '-missing')['reason'] == 'LINK_NOT_CONFIGURED'
    policy = '/api/simulation/scenarios/' + scenario_id + '/loads/' + load_id + '/link'
    def link(version, connected, lost):
        return call(8114, 'POST', policy, {'expectedVersion': version, 'connected': connected,
                    'acknowledgmentLost': lost, 'notBeforeTick': 0, 'provenance': 'synthetic fault test'}, run + '-link-' + str(version), 'admin')
    link(0, True, False)
    assert call(8114, 'POST', receive, channel, run + '-pending')['reason'] == 'DOWNLINK_EFFECT_NOT_OBSERVED'
    completed = call(8114, 'POST', advance_path, {'expectedVersion': imaged['version'], 'targetTick': 300}, run + '-downlink', 'admin')
    assert completed['body']['reservoirs']['storedMegabytes'] == 0
    link(1, False, False)
    assert call(8114, 'POST', receive, channel, run + '-disconnected')['reason'] == 'DISCONNECTED'
    link(2, True, True)
    assert call(8114, 'POST', receive, {'channel': 'ACKNOWLEDGMENT'}, run + '-lost')['reason'] == 'ACKNOWLEDGMENT_LOST'
    call(8114, 'POST', receive, channel, run + '-denied', 'requester', 403)
    received = call(8114, 'POST', receive, channel, run + '-received')
    assert received['belief'] == 'OBSERVED'
    assert received['receipt']['byteCount'] == 1_000_000
    assert received['receipt']['sha256'] == payload['body']['sha256']
    assert received['receipt']['stationId'] == station['id']
    assert call(8114, 'POST', receive, channel, run + '-received') == received
    assert call(8114, 'POST', receive, channel, run + '-missing')['belief'] == 'UNKNOWN'
    saved = call(8114, 'GET', '/internal/simulation/downlinks/' + plan['id'] + '/receipt')
    assert saved['body'] == received['receipt']
    link(3, False, False)
    assert call(8114, 'POST', receive, channel, run + '-later') == received
    report = {'scenarioId': scenario_id, 'planId': plan['id'], 'bookingId': booking['id'], 'payloadId': payload_id,
              'receipt': received['receipt'], 'checks': ['real IMAGE and S3 materialization', 'Orekit-backed Ground Operations booking',
              'pre-start payload binding', 'pending/disconnected/lost-ack UNKNOWN', 'verified DOWNLINK drain and receiver bytes',
              'reconciliation receipt and persisted read', 'replay preserves original UNKNOWN', 'receipt survives disconnection', 'requester denied'],
              'scope': 'Synthetic command-to-station flow; not production Control release, acquisition or fulfillment'}
    (api.ROOT / '.local/simulation-downlink-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
