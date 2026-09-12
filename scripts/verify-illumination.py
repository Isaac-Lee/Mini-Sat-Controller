#!/usr/bin/env python3
"""Verify the deployed sampled target-illumination API; not continuous AOI feasibility."""
import copy
import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def instant(value):
    assert value['scale'] == 'TAI'
    return value['seconds'] * 1_000_000_000 + value['nanos']


def intervals(windows):
    return [(instant(w['start']), instant(w['end'])) for w in windows]


def verify_gp(call):
    snapshot = call(8105, 'GET', '/api/tracked-satellites/63229/orbit')
    assert snapshot['elements']['noradId'] == 63229
    assert hashlib.sha256(snapshot['rawJson'].encode()).hexdigest() == snapshot['rawSha256']
    assert snapshot['id'] == 'gp-63229-' + snapshot['rawSha256']
    key = 'gp-eclipse-' + str(uuid.uuid4())
    imported = call(8103, 'POST', '/internal/public-orbits/import',
                    {'snapshotId': snapshot['id']}, key)
    assert imported['body'] == snapshot
    start = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': snapshot['elements']['epochUtc']})
    end = {**start, 'seconds': start['seconds'] + 10800}
    request = {'solutionId': snapshot['id'], 'horizon': {'start': start, 'end': end}}
    call(8103, 'POST', '/api/spacecraft-eclipse', request, key, 'requester', expected=403)
    saved = call(8103, 'POST', '/api/spacecraft-eclipse', request, key)
    assert call(8103, 'POST', '/api/spacecraft-eclipse', request, key) == saved
    body = saved['body']
    assert call(8103, 'GET', '/api/spacecraft-eclipse/' + saved['id'], user='operator1') == body
    assert body['solutionId'] == snapshot['id'] and body['spacecraftId'] == 'norad-63229'
    assert body['orbitPropagationModel'] == 'orekit-13.1.8-SGP4-SDP4-public-GP'
    assert 'UNCHARACTERISED' in body['solarModelAccuracyNote']
    assert body['horizon'] == request['horizon']
    changed = copy.deepcopy(request)
    changed['horizon']['end']['seconds'] -= 1
    call(8103, 'POST', '/api/spacecraft-eclipse', changed, key, expected=409)
    call(8103, 'POST', '/api/spacecraft-eclipse', {**request, 'solutionId': 'missing-' + key},
         key + '-missing', 'operator1', expected=404)
    segments = sorted(intervals(body['sunlitWindows']) + intervals(body['eclipseWindows']))
    assert segments and segments[0][0] == instant(start) and segments[-1][1] == instant(end)
    assert all(a < b for a, b in segments)
    assert all(segments[i - 1][1] == segments[i][0] for i in range(1, len(segments)))
    report = {'spacecraftEclipseId': saved['id'], 'sourceSnapshotId': snapshot['id'],
              'orbitPropagationModel': body['orbitPropagationModel'],
              'checks': ['source raw hash and NORAD binding', 'persistent exact source import',
                         'requester denied and operator route allowed', 'durable model-bound GP result',
                         'replay and changed-body conflict', 'complete disjoint interval partition'],
              'scope': 'GP eclipse API integration; solar accuracy remains uncharacterised'}
    (api.ROOT / '.local/gp-eclipse-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-gp', action='store_true', help='Also verify imported NORAD 63229 GP eclipse')
    args = parser.parse_args()
    key = 'illumination-' + str(uuid.uuid4())
    epoch = int(datetime(2026, 9, 11, tzinfo=timezone.utc).timestamp()) + 37
    tai = lambda value: {'seconds': value, 'nanos': 0, 'scale': 'TAI'}
    horizon = {'start': tai(epoch), 'end': tai(epoch + 86400)}
    request = {'spacecraftId': 'norad-63229', 'query': {
        'horizon': horizon, 'minimumSunElevationDegrees': 0,
        'aoi': {'id': 'synthetic-daejeon-box', 'westLongitudeDegrees': 127.3,
                'eastLongitudeDegrees': 127.5, 'southLatitudeDegrees': 36.2,
                'northLatitudeDegrees': 36.4, 'altitudeMeters': 100}}}
    call = api.call
    call(8103, 'POST', '/api/target-illumination', request, key, 'requester', expected=403)
    saved = call(8103, 'POST', '/api/target-illumination', request, key)
    body = saved['body']
    assert call(8103, 'POST', '/api/target-illumination', request, key) == saved
    assert call(8103, 'GET', '/api/target-illumination/' + saved['id'], user='operator1') == body
    changed = copy.deepcopy(request)
    changed['query']['minimumSunElevationDegrees'] = 5
    call(8103, 'POST', '/api/target-illumination', changed, key, expected=409)
    assert body['spacecraftId'] == request['spacecraftId'] and body['query'] == request['query']
    assert body['scope'] == 'SAMPLED_POINTS_ONLY' and 'UNCHARACTERISED' in body['solarModelAccuracyNote']
    # The reference archive itself is the authority for the digest.
    archive = api.ROOT / '.local/orekit/3e376b326373467647b1e246ebb083cd9e57cd68/time-frames.zip'
    assert body['referenceDigest'] == hashlib.sha256(archive.read_bytes()).hexdigest()
    assert len(body['points']) == 5 and len({p['pointId'] for p in body['points']}) == 5
    expected = [(instant(horizon['start']), instant(horizon['end']))]
    for point in body['points']:
        windows = intervals(point['illuminatedWindows'])
        assert windows
        assert all(instant(horizon['start']) <= a < b <= instant(horizon['end']) for a, b in windows)
        assert all(windows[i - 1][1] < windows[i][0] for i in range(1, len(windows)))
        expected = sorted((max(a, c), min(b, d)) for a, b in expected for c, d in windows
                          if max(a, c) < min(b, d))
    assert expected and intervals(body['sampledPointsIntersection']) == expected
    report = {'targetIlluminationId': saved['id'], 'scope': body['scope'],
              'checks': ['requester denied', 'durable operator read', 'idempotent replay',
                         'changed request conflict', 'source and scope binding',
                         'five-point window bounds and independent intersection']}
    (api.ROOT / '.local/illumination-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if args.with_gp:
        verify_gp(call)


if __name__ == '__main__':
    main()
