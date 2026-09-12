#!/usr/bin/env python3
"""Real local HTTP/DB reservation workflow against a stateful external-station simulator."""
import concurrent.futures
import datetime
import json
import math
from pathlib import Path
import runpy
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
call = runpy.run_path(str(ROOT/'scripts/verify-tasking.py'))['call']


def wait_booking(booking_id, status):
    until = time.monotonic()+30
    while time.monotonic() < until:
        value = call(8106, 'GET', '/api/bookings/'+booking_id, user='admin')
        if value['status'] == status:
            return value
        time.sleep(.1)
    raise AssertionError(('Expected booking status', status, value))


def main():
    for port in (8103, 8106, 8114):
        assert call(port, 'GET', '/actuator/health/readiness', user=None)['status'] == 'UP'
    run = uuid.uuid4().hex
    utc = (datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=180)).strftime('%Y-%m-%dT%H:%M:%S')
    epoch = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': utc}, user='service')
    spacecraft = 'sim-ground-'+run
    initial = {'solutionId': run, 'spacecraftId': spacecraft, 'epoch': epoch, 'positionMeters': {'x': 7000000, 'y': 0, 'z': 0}, 'velocityMetersPerSecond': {'x': 0, 'y': math.sqrt(3.986004418e14/7000000), 'z': 0}, 'provenance': 'Synthetic ground reservation verification'}
    call(8103, 'POST', '/internal/orbits', initial, run, user='service')
    end = {**epoch, 'seconds': epoch['seconds']+1}
    prediction = call(8103, 'POST', '/internal/predictions', {'solutionId': run, 'horizon': {'start': epoch, 'end': end}, 'stepSeconds': 1}, run, user='service')['body']
    point = call(8103, 'GET', '/internal/predictions/'+prediction['id']+'/samples', user='service')['groundTrack'][0]
    station = {'id': 'station-'+run, 'version': 1, 'latitudeDegrees': point['latitudeDegrees'], 'longitudeDegrees': point['longitudeDegrees'], 'altitudeMeters': 0, 'minimumElevationDegrees': 10, 'downlinkMegabytesPerSecond': 2, 'approvalReference': 'explicit simulation station fixture'}
    call(8106, 'POST', '/api/stations', station, run, user='admin')
    call(8114, 'POST', '/api/simulation/stations', station, run, user='admin')
    query = {'kind': 'GROUND_CONTACT', 'target': {'id': station['id']+':1', 'latitudeDegrees': station['latitudeDegrees'], 'longitudeDegrees': station['longitudeDegrees'], 'altitudeMeters': 0}, 'horizon': {'start': epoch, 'end': {**epoch, 'seconds': epoch['seconds']+600}}, 'minimumElevationDegrees': 10, 'maximumOffNadirDegrees': 30, 'minimumDurationSeconds': 10}
    access = call(8103, 'POST', '/internal/access-predictions', {'solutionId': run, 'query': query}, run, user='service')
    def request(start, end):
        return {'stationId': station['id'], 'stationVersion': 1, 'spacecraftId': spacecraft, 'window': {'start': {**epoch, 'seconds': epoch['seconds']+start}, 'end': {**epoch, 'seconds': epoch['seconds']+end}}, 'accessPredictionId': access['id'], 'requestedMegabytes': 5}
    body = request(10, 20)
    availability_query = {'horizon': query['horizon']}
    call(8106, 'POST', '/api/ground-availability', availability_query, run+'denied-calendar', expected=403)
    calendar = call(8106, 'POST', '/internal/ground-availability', availability_query, run+'calendar', user='service')
    def calendar_station(snapshot):
        return next(s for s in snapshot['stations'] if s['station']['id'] == station['id'])
    assert calendar_station(calendar['body'])['free'] == [query['horizon']]
    call(8106, 'POST', '/api/bookings', body, run+'denied', expected=403)
    call(8106, 'POST', '/api/bookings', {**body, 'spacecraftId': 'wrong-spacecraft'}, run+'wrong', user='admin', expected=400)
    call(8106, 'POST', '/api/bookings', {**body, 'requestedMegabytes': 1000}, run+'capacity', user='admin', expected=400)
    call(8114, 'POST', '/api/simulation/station-faults', {'stationId': station['id'], 'mode': 'LOSE_NEXT_RESERVATION_RESPONSE'}, user='admin')
    tentative = call(8106, 'POST', '/api/bookings', body, run+'lost', user='admin')
    assert tentative['body']['status'] == 'TENTATIVE'
    assert call(8106, 'POST', '/api/bookings', body, run+'lost', user='admin') == tentative
    confirmed = wait_booking(tentative['id'], 'CONFIRMED')
    history = call(8106, 'GET', '/api/bookings/'+confirmed['id']+'/history', user='admin')
    assert any(row['body']['status'] == 'UNKNOWN' for row in history)
    assert confirmed['evidenceReference'] == 'simulator-receipt:'+confirmed['id']
    call(8106, 'POST', '/api/bookings', request(15, 25), run+'overlap', user='admin', expected=409)
    adjacent = call(8106, 'POST', '/api/bookings', request(20, 30), run+'adjacent', user='admin')
    wait_booking(adjacent['id'], 'CONFIRMED')
    occupied = call(8106, 'POST', '/internal/ground-availability', availability_query, run+'occupied', user='service')
    assert len(calendar_station(occupied['body'])['allocations']) == 2
    assert calendar_station(occupied['body'])['free'] == [
        {'start': epoch, 'end': body['window']['start']},
        {'start': request(20,30)['window']['end'], 'end': query['horizon']['end']}]
    call(8106, 'POST', '/api/bookings/'+confirmed['id']+'/cancel', key=run+'cancel', user='admin')
    wait_booking(confirmed['id'], 'CANCELLED')
    assert call(8114, 'GET', '/internal/station-bookings/'+confirmed['id'], user='service')['status'] == 'CANCELLED'
    retried = call(8114, 'POST', '/internal/station-bookings/'+confirmed['id'], body, confirmed['id']+':reserve', user='service')
    assert retried['status'] == 'CANCELLED'
    released = call(8106, 'POST', '/internal/ground-availability', availability_query, run+'released', user='service')
    assert calendar_station(released['body'])['free'][0]['end'] == request(20,30)['window']['start']
    assert call(8106, 'GET', '/internal/ground-availability/'+occupied['id'], user='service') == occupied['body']
    assert call(8106, 'POST', '/internal/ground-availability', availability_query, run+'calendar', user='service') == calendar
    def competing(index):
        try:
            return call(8106, 'POST', '/api/bookings', body, run+'race'+str(index), user='admin')['id']
        except AssertionError as error:
            assert error.args[0][1] == 409, error
            return 'conflict'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        contenders = list(pool.map(competing, [1, 2]))
    assert contenders.count('conflict') == 1
    winner = next(value for value in contenders if value != 'conflict')
    wait_booking(winner, 'CONFIRMED')
    call(8114, 'POST', '/api/simulation/station-faults', {'stationId': station['id'], 'mode': 'WRONG_NEXT_RESERVATION_RECEIPT'}, user='admin')
    bad_reply = call(8106, 'POST', '/api/bookings', request(30, 40), run+'bad-reply', user='admin')
    repaired = wait_booking(bad_reply['id'], 'CONFIRMED')
    repaired_history = call(8106, 'GET', '/api/bookings/'+bad_reply['id']+'/history', user='admin')
    assert any(row['body']['status'] == 'UNKNOWN' for row in repaired_history)
    assert all(row['body']['evidenceReference'] != 'simulated-corrupt-receipt' for row in repaired_history)
    assert repaired['evidenceReference'] == 'simulator-receipt:'+repaired['id']
    tombstone = str(uuid.uuid4())
    cancelled = call(8114, 'POST', '/internal/station-bookings/'+tombstone+'/cancel', request(40, 50), tombstone+'cancel', user='service')
    assert cancelled['status'] == 'CANCELLED'
    late = call(8114, 'POST', '/internal/station-bookings/'+tombstone, request(40, 50), tombstone+'reserve', user='service')
    assert late['status'] == 'CANCELLED'
    result = {'passed': ['Orekit-backed contact binding', 'capacity and role rejection', 'lost reply reconciled by ID', 'overlap exclusion and adjacent windows', 'external cancellation before local release', 'single concurrent reservation winner', 'mismatched receipt rejected then reconciled', 'cancellation tombstone defeats delayed create', 'allocation snapshots and immutable history', 'free intervals after confirmed cancellation'], 'bookingId': repaired['id'], 'stationId': station['id'], 'availabilitySnapshotId': occupied['id'], 'scope': 'Stateful station simulation; no spacecraft uplink or pass execution'}
    (ROOT/'.local/ground-verification.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
