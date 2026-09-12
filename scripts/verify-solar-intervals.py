#!/usr/bin/env python3
"""Verify owner-pinned solar intervals across deployed services on an isolated simulation fixture."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    fixture = json.loads((api.ROOT / '.local/simulation-correlation-verification.json').read_text())
    original = call(8104, 'GET', '/internal/missions/' + fixture['spacecraftId'])
    craft = 'solar-verification-' + str(uuid.uuid4())
    mission = {**original, 'spacecraftId': craft, 'provenance': 'isolated solar API fixture'}
    call(8104, 'POST', '/api/missions', mission, craft, 'admin')
    reference = call(8103, 'GET', '/internal/reference-context')
    start = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': '2026-09-01T12:00:00'})
    horizon = {'start': start, 'end': {**start, 'seconds': start['seconds'] + 10}}
    area = {'id': craft, 'westLongitudeDegrees': -10, 'eastLongitudeDegrees': 10,
            'southLatitudeDegrees': -10, 'northLatitudeDegrees': 10, 'altitudeMeters': 0}
    assumptions = {'spacecraftId': craft, 'missionDefinitionVersion': mission['missionDefinitionVersion'],
                   'environment': 'SIMULATION', 'solarModel': 'orekit-13.1.8-analytical-solar-position',
                   'referenceDigest': reference['digest'], 'validInterval': horizon, 'extent': area,
                   'maximumRateRadiansPerSecond': .001, 'evaluationErrorRadians': .0001,
                   'maximumStepSeconds': 5, 'justificationReference': 'synthetic integration assumption, not physical qualification'}
    publish = {'expectedVersion': 0, 'assumptions': assumptions}
    call(8104, 'POST', '/api/solar-interval-assumptions', publish, craft, 'operator1', 403)
    source = call(8104, 'POST', '/api/solar-interval-assumptions', publish, craft, 'admin')
    request = {'spacecraftId': craft, 'assumptionsVersion': 1,
               'query': {'aoi': area, 'horizon': horizon, 'minimumSunElevationDegrees': 10}}
    call(8103, 'POST', '/api/solar-intervals', request, craft, 'requester', 403)
    first = call(8103, 'POST', '/internal/solar-intervals', request, craft)
    result = first['body']
    assert result['assumptions'] == source
    assert result['outcome'] == 'SUPPORTED_BY_DECLARED_ASSUMPTIONS'
    assert len(result['interval']['cells']) == 2
    assert len(result['assumptionsSha256']) == 64
    assert call(8103, 'GET', '/internal/solar-intervals/' + first['id']) == result
    revised = {**assumptions, 'evaluationErrorRadians': 2}
    call(8104, 'POST', '/api/solar-interval-assumptions', {'expectedVersion': 1, 'assumptions': revised}, craft + '-v2', 'admin')
    assert call(8103, 'POST', '/internal/solar-intervals', request, craft) == first
    second = call(8103, 'POST', '/internal/solar-intervals', {**request, 'assumptionsVersion': 2}, craft + '-v2')
    assert second['body']['outcome'] == 'NOT_ESTABLISHED'
    call(8103, 'POST', '/internal/solar-intervals', {**request, 'assumptionsVersion': 999}, craft + '-missing', expected=404)
    outside = {**request, 'query': {**request['query'], 'aoi': {**area, 'westLongitudeDegrees': -11}}}
    call(8103, 'POST', '/internal/solar-intervals', outside, craft + '-outside', expected=400)
    report = {'spacecraftId': craft, 'resultId': first['id'], 'assumptionsVersions': [1, 2],
              'checks': ['admin-only assumptions', 'requester compute denied', 'exact owner revision retained',
                         'conditional positive interval and persisted read', 'replay survives owner update',
                         'changed assumptions yield NOT_ESTABLISHED', 'missing owner version rejected', 'outside extent rejected'],
              'scope': 'Real service-to-service simulation calculation using test-only declared assumptions'}
    (api.ROOT / '.local/solar-interval-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
