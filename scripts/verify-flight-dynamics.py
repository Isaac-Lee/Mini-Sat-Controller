#!/usr/bin/env python3
"""Exercise a running local Flight Dynamics service using simulation inputs only."""
import base64
import hashlib
import json
import math
from pathlib import Path
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
ENV = dict(line.split('=', 1) for line in (ROOT/'.local/msa.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
BASE = 'http://127.0.0.1:8103'


def call(method, path, body=None, key=None, user='service', expected=200, expected_digest=None):
    payload = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=payload, method=method)
    if user:
        password_key = {'service': 'MSC_SERVICE_PASSWORD', 'requester': 'MSC_LOCAL_REQUESTER_PASSWORD', 'admin': 'MSC_LOCAL_ADMIN_PASSWORD'}[user]
        request.add_header('Authorization', 'Basic ' + base64.b64encode((user+':'+ENV[password_key]).encode()).decode())
    request.add_header('Content-Type', 'application/json')
    if key:
        request.add_header('Idempotency-Key', key)
    try:
        response = urllib.request.urlopen(request, timeout=45)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        assert response.status == expected, (path, response.status, raw[:300])
        if expected_digest:
            assert hashlib.sha256(raw).hexdigest() == expected_digest
        return json.loads(raw) if raw else None


def main():
    run = str(uuid.uuid4())
    assert call('GET', '/actuator/health/readiness', user=None)['status'] == 'UP'
    call('GET', '/api/orbits/missing', user=None, expected=401)
    epoch = call('POST', '/internal/time/utc-to-tai', {'utc': '2026-09-01T12:00:00'})
    orbit = {'solutionId': 'sim-'+run, 'spacecraftId': 'sim-sat-1', 'epoch': epoch,
             'positionMeters': {'x': 7000000, 'y': 0, 'z': 0},
             'velocityMetersPerSecond': {'x': 0, 'y': math.sqrt(3.986004418e14/7000000), 'z': 0},
             'provenance': 'Local synthetic circular orbit; no operational designation'}
    call('POST', '/api/orbits', orbit, run, 'requester', 403)
    saved = call('POST', '/internal/orbits', orbit, run)
    assert call('POST', '/internal/orbits', orbit, run) == saved
    end = {**epoch, 'seconds': epoch['seconds'] + 120}
    query = {'solutionId': orbit['solutionId'], 'horizon': {'start': epoch, 'end': end}, 'stepSeconds': 30}
    prediction = call('POST', '/internal/predictions', query, run)
    assert call('POST', '/internal/predictions', query, run) == prediction
    call('POST', '/internal/predictions', {**query, 'stepSeconds': 60}, run, expected=409)
    manifest = prediction['body']
    assert manifest['sampleCount'] == 4
    assert manifest['model'].startswith('orekit-13.1.8-keplerian')
    doc = call('GET', '/internal/predictions/'+manifest['id']+'/samples', expected_digest=manifest['objectReference'].rsplit('/', 1)[1])
    assert len(doc['prediction']['samples']) == 4 and len(doc['groundTrack']) == 4
    assert doc['referenceDigest'] == manifest['referenceDigest']
    assert 621000 < doc['groundTrack'][0]['altitudeMeters'] < 623000
    late = call('POST', '/internal/time/utc-to-tai', {'utc': '2200-01-01T00:00:00'})
    bad = {**query, 'horizon': {'start': late, 'end': {**late, 'seconds': late['seconds'] + 60}}}
    call('POST', '/internal/predictions', bad, str(uuid.uuid4()), expected=400)
    result = {'passed': ['readiness', 'authentication', 'role denial', 'immutable orbit',
                         'idempotency and changed-body conflict', 'Orekit propagation',
                         'S3 write and streamed read', 'reference provenance', 'stale input rejection'],
              'predictionId': manifest['id'], 'objectReference': manifest['objectReference'],
              'referenceDigest': manifest['referenceDigest']}
    output = ROOT/'.local/flight-dynamics-verification.json'
    output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
