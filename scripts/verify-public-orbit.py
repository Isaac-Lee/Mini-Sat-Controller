#!/usr/bin/env python3
"""Local API verification. --collect enables one target; only its collector calls CelesTrak."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
ENV = dict(line.split('=', 1) for line in (ROOT / '.local/msa.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))


def call(port, method, path, body=None, key=None, user='service', expected=200):
    port = int(os.environ.get(f'MSC_VERIFY_PORT_{port}', port))
    passwords = {'service': 'MSC_SERVICE_PASSWORD', 'admin': 'MSC_LOCAL_ADMIN_PASSWORD',
                 'requester': 'MSC_LOCAL_REQUESTER_PASSWORD',
                 'operator1': 'MSC_LOCAL_OPERATOR_PASSWORD', 'operator2': 'MSC_LOCAL_OPERATOR_PASSWORD'}
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header('Content-Type', 'application/json')
    req.add_header('Authorization', 'Basic ' + base64.b64encode(
        (user + ':' + ENV[passwords[user]]).encode()).decode())
    if key:
        req.add_header('Idempotency-Key', key)
    try:
        res = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as error:
        res = error
    with res:
        raw = res.read()
        assert res.status in ([expected] if isinstance(expected, int) else expected), (path, res.status, raw[:300])
        return json.loads(raw) if raw else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collect', action='store_true', help='Enable periodic collection for the selected NORAD ID')
    parser.add_argument('--norad-id', type=int, default=63229)
    args = parser.parse_args()
    assert 1 <= args.norad_id <= 999999999
    norad = args.norad_id
    tracked = f'/api/tracked-satellites/{norad}'
    if args.collect:
        body = {'noradId': norad, 'displayName': 'SPACEEYE-T1' if norad == 63229 else f'NORAD-{norad}', 'enabled': True}
        call(8105, 'PUT', tracked, body, user='requester', expected=403)
        call(8105, 'PUT', tracked, body, user='admin')
        outcome = call(8105, 'POST', tracked + '/refresh', user='admin', expected=[200, 409])
        if outcome.get('last_error'):
            raise RuntimeError(outcome['last_error'])
    snapshot = None
    for _ in range(30):
        candidate = call(8105, 'GET', tracked + '/orbit', expected=[200, 404])
        if candidate.get('elements'):
            snapshot = candidate
            break
        time.sleep(1)
    assert snapshot, 'No collected snapshot; inspect provider/satellite error status'
    assert snapshot['elements']['noradId'] == norad
    assert hashlib.sha256(snapshot['rawJson'].encode()).hexdigest() == snapshot['rawSha256']
    run = str(uuid.uuid4())
    source = {'snapshotId': snapshot['id']}
    call(8103, 'POST', '/api/public-orbits/import', source, run, 'requester', 403)
    saved = call(8103, 'POST', '/internal/public-orbits/import', source, run)
    assert call(8103, 'POST', '/internal/public-orbits/import', source, run) == saved
    assert saved['body'] == snapshot
    start = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')})
    end = {**start, 'seconds': start['seconds'] + 5400}
    query = {'horizon': {'start': start, 'end': end}, 'stepSeconds': 60}
    base = '/internal/public-orbits/' + snapshot['id']
    result = call(8103, 'POST', base + '/predictions', query, run)
    assert call(8103, 'POST', base + '/predictions', query, run) == result
    call(8103, 'POST', base + '/predictions', {**query, 'stepSeconds': 120}, run, expected=409)
    manifest = result['body']
    assert manifest['spacecraftId'] == f'norad-{norad}'
    assert 'SGP4' in manifest['model'] and manifest['frame'] == 'EME2000'
    doc = call(8103, 'GET', '/internal/predictions/' + manifest['id'] + '/samples')
    assert len(doc['groundTrack']) == 90
    assert all(-90 <= p['latitudeDegrees'] <= 90 and -180 <= p['longitudeDegrees'] <= 180 for p in doc['groundTrack'])
    access_query = {'kind': 'GROUND_CONTACT', 'target': {'id': 'test-daejeon-location', 'latitudeDegrees': 36.35,
                    'longitudeDegrees': 127.38, 'altitudeMeters': 100},
                    'horizon': {'start': start, 'end': {**start, 'seconds': start['seconds'] + 86400}},
                    'minimumElevationDegrees': 5, 'maximumOffNadirDegrees': 30, 'minimumDurationSeconds': 10}
    access = call(8103, 'POST', base + '/access-predictions', access_query, run)['body']
    assert access['spacecraftId'] == f'norad-{norad}' and 'SGP4' in access['model']
    if norad == 63229:
        assert access['windows']
    stale = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': '2200-01-01T00:00:00'})
    call(8103, 'POST', base + '/predictions', {'horizon': {'start': stale, 'end': {**stale, 'seconds': stale['seconds'] + 60}},
         'stepSeconds': 60}, str(uuid.uuid4()), expected=400)
    call(8105, 'POST', tracked + '/refresh', user='admin', expected=409)
    evidence = {'noradId': norad, 'name': snapshot['elements']['name'], 'epochUtc': snapshot['elements']['epochUtc'],
                'snapshotId': snapshot['id'], 'predictionId': manifest['id'], 'sampleCount': manifest['sampleCount'],
                'daejeonGeometricContactCount': len(access['windows']), 'model': manifest['model'],
                'firstPredictedGroundPoint': doc['groundTrack'][0],
                'passed': ['persistent reference and raw hash', 'API role denial', 'pinned reference service import',
                           'idempotency and conflict', 'SGP4 and TEME-to-EME2000 conversion', 'S3 ground track',
                           'geometric contacts', 'stale epoch rejection', 'durable refresh cooldown'],
                'scope': 'Public orbit prediction; no live spacecraft telemetry or actual station contact'}
    (ROOT / '.local/public-orbit-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
