#!/usr/bin/env python3
"""Verify deployed target-pointing geometry against independent WGS84 vector arithmetic."""
import hashlib
import importlib
import json
import math
import uuid

api = importlib.import_module('verify-public-orbit')


def vector(value):
    return tuple(value[k] for k in ('x', 'y', 'z'))


def norm(value):
    return math.sqrt(sum(x * x for x in value))


def angle(a, b):
    cosine = sum(x * y for x, y in zip(a, b)) / (norm(a) * norm(b))
    return math.degrees(math.acos(max(-1, min(1, cosine))))


def earth_fixed(target):
    latitude = math.radians(target['latitudeDegrees'])
    longitude = math.radians(target['longitudeDegrees'])
    flattening = 1 / 298.257223563
    eccentricity_squared = flattening * (2 - flattening)
    radius = 6378137 / math.sqrt(1 - eccentricity_squared * math.sin(latitude) ** 2)
    altitude = target['altitudeMeters']
    return ((radius + altitude) * math.cos(latitude) * math.cos(longitude),
            (radius + altitude) * math.cos(latitude) * math.sin(longitude),
            (radius * (1 - eccentricity_squared) + altitude) * math.sin(latitude))


def main():
    run = str(uuid.uuid4())
    snapshot = api.call(8105, 'GET', '/api/tracked-satellites/63229/orbit')
    raw_hash = hashlib.sha256(snapshot['rawJson'].encode()).hexdigest()
    assert raw_hash == snapshot['rawSha256']
    imported = api.call(8103, 'POST', '/internal/public-orbits/import',
                        {'snapshotId': snapshot['id']}, run)
    assert imported['body'] == snapshot
    start = api.call(8103, 'POST', '/internal/time/utc-to-tai',
                     {'utc': snapshot['elements']['epochUtc']})
    target = {'id': 'synthetic-daejeon-pointing-check', 'latitudeDegrees': 36.35,
              'longitudeDegrees': 127.38, 'altitudeMeters': 100}
    query = {'target': target, 'horizon': {'start': start,
             'end': {**start, 'seconds': start['seconds'] + 600}},
             'stepSeconds': 60, 'minimumElevationDegrees': 5}
    request = {'solutionId': snapshot['id'], 'query': query}
    path = '/api/required-target-pointing'
    api.call(8103, 'POST', path, request, run, user='requester', expected=403)
    saved = api.call(8103, 'POST', path, request, run)
    assert api.call(8103, 'POST', path, request, run) == saved
    result = saved['body']
    assert api.call(8103, 'GET', path + '/' + saved['id']) == result
    assert result['solutionId'] == snapshot['id'] and result['spacecraftId'] == 'norad-63229'
    assert result['orbitSourceHash'] == raw_hash and result['referenceDigest']
    assert 'SGP4' in result['orbitPropagationModel']
    assert result['scope'] == 'SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE'
    assert result['query'] == query and len(result['samples']) == 10
    target_position = earth_fixed(target)
    lat, lon = math.radians(target['latitudeDegrees']), math.radians(target['longitudeDegrees'])
    up = (math.cos(lat) * math.cos(lon), math.cos(lat) * math.sin(lon), math.sin(lat))
    north = (-math.sin(lat) * math.cos(lon), -math.sin(lat) * math.sin(lon), math.cos(lat))
    east = (-math.sin(lon), math.cos(lon), 0)
    max_range_error = 0
    for index, sample in enumerate(result['samples']):
        assert sample['instant'] == {**start, 'seconds': start['seconds'] + index * 60}
        assert sample['target'] == target
        position = vector(sample['satellitePositionEarthFixedMeters'])
        los = tuple(t - p for t, p in zip(target_position, position))
        distance = norm(los)
        error = abs(sample['slantRangeMeters'] - distance)
        max_range_error = max(max_range_error, error)
        assert error < 0.001
        unit = vector(sample['lineOfSightEarthFixedUnit'])
        assert norm(tuple(x - y / distance for x, y in zip(unit, los))) < 1e-10
        assert abs(norm(vector(sample['lineOfSightInertialUnit'])) - 1) < 1e-10
        assert abs(sample['requiredOffNadirDegrees'] - angle(tuple(-x for x in position), los)) < 1e-6
        ground_to_satellite = tuple(-x for x in los)
        elevation = 90 - angle(up, ground_to_satellite)
        azimuth = math.degrees(math.atan2(sum(x * y for x, y in zip(east, ground_to_satellite)),
                                          sum(x * y for x, y in zip(north, ground_to_satellite)))) % 360
        assert abs(elevation - sample['topocentricElevationDegrees']) < 1e-6
        assert abs((azimuth - sample['topocentricAzimuthDegrees'] + 180) % 360 - 180) < 1e-6
        assert sample['targetVisible'] == (elevation >= query['minimumElevationDegrees'])
    changed = {**request, 'query': {**query, 'stepSeconds': 120}}
    api.call(8103, 'POST', path, changed, run, expected=409)
    api.call(8103, 'POST', path, {**request, 'solutionId': 'absent-' + run}, run + '-missing', expected=404)
    api.call(8103, 'POST', path, {**request, 'query': {**query, 'stepSeconds': 0}},
             run + '-invalid', expected=400)
    assert api.call(8105, 'GET', '/api/orbit-references/' + snapshot['id']) == snapshot
    evidence = {'snapshotId': snapshot['id'], 'pointingId': saved['id'],
                'referenceDigest': result['referenceDigest'], 'sampleCount': 10,
                'maximumSlantRangeErrorMeters': max_range_error,
                'checks': ['owned GP import and raw hash', 'HTTP persistence and idempotency',
                           'independent WGS84 LOS, slant range, radial off-nadir and topocentric angles',
                           'requester write denial', 'conflict, missing orbit and invalid sampling rejection',
                           'original reference preservation'],
                'scope': 'Sampled required pointing only; inertial vector norm checked, full frame rotation covered by Orekit tests; no attitude or coverage proof'}
    (api.ROOT / '.local/required-target-pointing-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
