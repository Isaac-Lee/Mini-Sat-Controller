#!/usr/bin/env python3
"""Live numerical access and atomic orbit-designation verification; local synthetic inputs only."""
import concurrent.futures
import datetime
import json
import math
from pathlib import Path
import runpy
import uuid

ROOT = Path(__file__).resolve().parents[1]
call = runpy.run_path(str(ROOT/'scripts/verify-flight-dynamics.py'))['call']


def main():
    run = str(uuid.uuid4())
    epoch = call('POST', '/internal/time/utc-to-tai', {'utc': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')})
    spacecraft = 'sim-access-' + run
    initial = {'solutionId': run, 'spacecraftId': spacecraft, 'epoch': epoch,
               'positionMeters': {'x': 7000000, 'y': 0, 'z': 0},
               'velocityMetersPerSecond': {'x': 0, 'y': math.sqrt(3.986004418e14/7000000), 'z': 0},
               'provenance': 'Synthetic local access/selection verification'}
    call('POST', '/internal/orbits', initial, run)
    prediction = call('POST', '/internal/predictions', {'solutionId': run, 'horizon': {'start': epoch, 'end': {**epoch, 'seconds': epoch['seconds']+1}}, 'stepSeconds': 1}, run)['body']
    point = call('GET', '/internal/predictions/'+prediction['id']+'/samples')['groundTrack'][0]
    target = {'id': 'sim-target', 'latitudeDegrees': point['latitudeDegrees'], 'longitudeDegrees': point['longitudeDegrees'], 'altitudeMeters': 0}
    query = {'kind': 'POINT_IMAGING', 'target': target, 'horizon': {'start': epoch, 'end': {**epoch, 'seconds': epoch['seconds']+1800}}, 'minimumElevationDegrees': 10, 'maximumOffNadirDegrees': 15, 'minimumDurationSeconds': 10}
    body = {'solutionId': run, 'query': query}
    access = call('POST', '/internal/access-predictions', body, run)
    assert len(access['body']['windows']) == 1
    assert call('POST', '/internal/access-predictions', body, run) == access
    assert call('GET', '/internal/access-predictions/'+access['id']) == access['body']
    call('POST', '/api/access-predictions', body, run, user='requester', expected=403)
    contact = call('POST', '/internal/access-predictions', {'solutionId': run, 'query': {**query, 'kind': 'GROUND_CONTACT'}}, run+'contact')
    assert contact['body']['windows'][0]['end']['seconds'] > access['body']['windows'][0]['end']['seconds']
    assert access['body']['referenceDigest'] == prediction['referenceDigest']
    selection = {'solutionId': run, 'expectedVersion': 0, 'decisionReference': 'Synthetic simulation selection only'}
    path = '/api/orbit-designations/'+spacecraft
    call('POST', path, selection, run, user='requester', expected=403)
    selected = call('POST', path, selection, run, user='admin')
    assert selected['version'] == 1
    assert call('POST', path, selection, run, user='admin') == selected
    call('POST', '/api/orbit-designations/wrong-spacecraft', selection, run+'wrong', user='admin', expected=400)
    update = {**selection, 'expectedVersion': 1}
    def race(i):
        try:
            return call('POST', path, update, run+str(i), user='admin')['version']
        except AssertionError as error:
            assert error.args[0][1] == 409, error
            return 'conflict'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(race, [1, 2]))
    assert sorted(map(str, results)) == ['2', 'conflict'], results
    current = call('GET', '/internal/orbit-designations/'+spacecraft)
    assert current['version'] == 2 and current['orbitSolutionId']['value'] == run
    result = {'passed': ['numerical imaging/contact windows', 'persistent access lookup', 'idempotency',
                         'role denial', 'cross-spacecraft rejection', 'concurrent designation CAS'],
              'accessId': access['id'], 'spacecraftId': spacecraft, 'designationVersion': 2,
              'referenceDigest': access['body']['referenceDigest']}
    (ROOT/'.local/access-verification.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
