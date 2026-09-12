#!/usr/bin/env python3
"""Check deployed camera evidence against independent ray/plane arithmetic and owned MinIO bytes."""
import hashlib
import importlib
import json
import math
import subprocess
import urllib.parse
import uuid

api = importlib.import_module('verify-public-orbit')
geometry = importlib.import_module('verify-required-target-pointing')


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def unit(a):
    length = math.sqrt(dot(a, a))
    return tuple(x / length for x in a)


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def projected_corners(sample, camera):
    target = sample['target']
    lat, lon = map(math.radians, (target['latitudeDegrees'], target['longitudeDegrees']))
    up = (math.cos(lat)*math.cos(lon), math.cos(lat)*math.sin(lon), math.sin(lat))
    north = (-math.sin(lat)*math.cos(lon), -math.sin(lat)*math.sin(lon), math.cos(lat))
    east = (-math.sin(lon), math.cos(lon), 0)
    relative = tuple(s-t for s, t in zip(geometry.vector(sample['satellitePositionEarthFixedMeters']),
                                       geometry.earth_fixed(target)))
    bore = unit(tuple(-v for v in relative))
    along = unit(tuple(n-dot(north, bore)*b for n, b in zip(north, bore)))
    across = cross(along, bore)
    tx, ty = map(lambda x: math.tan(math.radians(x)),
                 (camera['halfAngleAcrossDegrees'], camera['halfAngleAlongDegrees']))
    points = []
    for x, y in ((-tx, -ty), (tx, -ty), (tx, ty), (-tx, ty)):
        ray = tuple(b+x*a+y*l for b, a, l in zip(bore, across, along))
        factor = -dot(up, relative)/dot(up, ray)
        point = tuple(s+factor*d for s, d in zip(relative, ray))
        points.append((dot(point, east), dot(point, north)))
    return points


def read_object(reference):
    parsed = urllib.parse.urlsplit(reference)
    assert parsed.scheme == 's3' and parsed.netloc == 'msc-flight-dynamics'
    config = '\n'.join([
        'url = ' + json.dumps('http://127.0.0.1:59000/' + parsed.netloc + parsed.path),
        'aws-sigv4 = "aws:amz:us-east-1:s3"',
        'user = ' + json.dumps(api.ENV['MSC_S3_ACCESS_KEY'] + ':' + api.ENV['MSC_S3_SECRET_KEY']),
        'fail', 'silent', 'show-error', 'max-time = 30'])
    response = subprocess.run(['curl', '--config', '-'], input=config.encode(), capture_output=True)
    assert response.returncode == 0, 'Owned MinIO evidence read failed'
    return response.stdout


def main():
    fixture = json.loads((api.ROOT / '.local/planning-search-verification.json').read_text())
    run = api.call(8102, 'GET', '/api/planning/runs/' + fixture['runId'])
    craft = run['spacecraftId']
    assert craft.startswith('000-sim-search-')
    owned = run['geometry']['value']
    prediction = owned['prediction']['body']
    camera = api.call(8104, 'GET', '/internal/simulation-camera-models/' + craft)
    planning = api.call(8104, 'GET', '/internal/simulation-planning-models/' + craft
                        + '/versions/' + str(camera['body']['simulationPlanningModelVersion']))
    window = prediction['windows'][0]
    key = 'camera-eval-' + uuid.uuid4().hex
    pointing_query = {'target': prediction['query']['target'], 'horizon': window,
                      'stepSeconds': 10, 'minimumElevationDegrees': 0}
    pointing = api.call(8103, 'POST', '/api/required-target-pointing',
                        {'solutionId': prediction['solutionId'], 'query': pointing_query}, key)
    path = '/api/camera-footprint-evaluations'
    query = {'pointingResultId': pointing['id'], 'cameraModelVersion': camera['version'],
             'area': owned['area']}
    request = {'query': query}
    api.call(8103, 'POST', path, request, key, 'requester', 403)
    saved = api.call(8103, 'POST', path, request, key)
    assert api.call(8103, 'POST', path, request, key) == saved
    manifest = api.call(8103, 'GET', path + '/' + saved['id'])
    assert manifest == saved['body']
    result = api.call(8103, 'GET', path + '/' + saved['id'] + '/result')
    raw = read_object(manifest['objectReference'])
    assert json.loads(raw) == result and len(raw) <= 16*1024*1024
    assert result['cameraModel'] == camera and result['planningModel'] == planning
    assert result['scope'] == 'SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE'
    assert result['aoiMapping']['mapping'] == 'WGS84_LOCAL_LINEAR_RECTANGLE_V1'
    assert result['query'] == query
    assert len(result['samples']) == len(pointing['body']['samples']) == manifest['sampleCount']
    maximum_corner_error = 0
    for sample, value in zip(pointing['body']['samples'], result['samples']):
        assert value['instant'] == sample['instant'] and value['outcome'] == 'COMPUTED'
        expected = projected_corners(sample, camera['body'])
        actual = [(p['eastMeters'], p['northMeters']) for p in value['footprintCorners']]
        # Match corners irrespective of polygon winding or starting corner.
        error = max(min(math.dist(p, q) for q in expected) for p in actual)
        maximum_corner_error = max(maximum_corner_error, error)
        assert error < .001
        area = abs(sum(expected[i][0]*expected[(i+1)%4][1]
                       - expected[(i+1)%4][0]*expected[i][1] for i in range(4)))/2
        assert abs(value['footprintAreaSquareMeters']-area) < max(.01, area*1e-10)
        assert value['coverageFraction'] > .999999
        assert value['exceedsMaximumOffNadir'] == (sample['requiredOffNadirDegrees']
                                                  > camera['body']['maximumOffNadirDegrees'])
        assert value['exceedsMaximumGroundSampleDistance'] == (
            value['maximumPixelAxisSpacingBoundMeters'] > camera['body']['maximumGroundSampleDistanceMeters'])
    changed = {'query': {**query, 'cameraModelVersion': camera['version'] + 1}}
    api.call(8103, 'POST', path, changed, key, expected=409)
    bad_area = {**query['area'], 'west': query['area']['west']+.0001}
    api.call(8103, 'POST', path, {'query': {**query, 'area': bad_area}}, key+'-center', expected=400)
    api.call(8103, 'GET', '/internal/camera-footprint-evaluations/'+saved['id'],
             user='operator1', expected=403)
    assert api.call(8102, 'GET', '/api/planning/runs/' + fixture['runId']) == run
    evidence = {'spacecraftId': craft, 'pointingId': pointing['id'], 'evaluationId': saved['id'],
                'sampleCount': manifest['sampleCount'], 'cameraModelVersion': camera['version'],
                'objectReference': manifest['objectReference'], 'objectBytes': len(raw),
                'objectSha256': hashlib.sha256(raw).hexdigest(),
                'maximumIndependentCornerErrorMeters': maximum_corner_error,
                'checks': ['HTTP owner model chain and immutable source envelopes',
                           'MinIO bytes match streamed result', 'independent ray-plane corners and area',
                           'full planar coverage at sampled instants', 'replay and changed-body conflict',
                           'role and AOI-centre rejection', 'Planning run unchanged'],
                'scope': 'Sampled synthetic footprint only; no continuous exposure or schedule authorization'}
    (api.ROOT / '.local/camera-footprint-evaluation-verification.json').write_text(
        json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
