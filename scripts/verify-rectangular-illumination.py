#!/usr/bin/env python3
"""Exercise the deployed rectangular illumination owner API using local credentials privately."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    run = str(uuid.uuid4())
    start = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': '2026-09-01T12:00:00'})
    end = {**start, 'seconds': start['seconds'] + 3600}
    request = {'spacecraftId': 'norad-63229', 'query': {
        'aoi': {'id': 'rectangle-' + run, 'westLongitudeDegrees': -10,
                'eastLongitudeDegrees': 10, 'southLatitudeDegrees': -10,
                'northLatitudeDegrees': 10, 'altitudeMeters': 0},
        'horizon': {'start': start, 'end': end}, 'minimumSunElevationDegrees': 10}}
    path = '/api/rectangular-illumination'
    call(8103, 'POST', path, request, run, 'requester', 403)
    saved = call(8103, 'POST', path, request, run, 'operator1')
    assert call(8103, 'POST', path, request, run, 'operator1') == saved
    result = saved['body']
    assert result['query'] == request['query']
    assert result['spacecraftId'] == 'norad-63229'
    assert result['scope'] == 'SPATIAL_BOUND_NUMERICAL_EVENT_SEARCH'
    assert len(result['referenceDigest']) == 64
    assert result['rootToleranceSeconds'] == .001 and result['maximumCheckSeconds'] == 60
    assert result['illuminatedWindows'] == [{'start': start, 'end': end}]
    assert call(8103, 'GET', '/internal/rectangular-illumination/' + saved['id']) == result
    changed = {**request, 'spacecraftId': 'different-craft'}
    call(8103, 'POST', path, changed, run, 'operator1', 409)
    dark = {**request, 'query': {**request['query'], 'minimumSunElevationDegrees': 89}}
    assert call(8103, 'POST', '/internal/rectangular-illumination', dark, run + '-dark')['body']['illuminatedWindows'] == []
    too_long = {**request, 'query': {**request['query'], 'horizon': {
        'start': start, 'end': {**start, 'seconds': start['seconds'] + 86401}}}}
    call(8103, 'POST', path, too_long, run + '-long', 'operator1', 400)
    # The older five-point contract must retain its original scope after the new deployment.
    legacy = call(8103, 'POST', '/internal/target-illumination', request, run + '-sampled')
    assert legacy['body']['scope'] == 'SAMPLED_POINTS_ONLY'
    report = {'id': saved['id'], 'checks': [
        'requester denied', 'pinned query and numerical scope retained', 'noon rectangle illuminated',
        'same-key replay identical', 'persisted read identical', 'same-key changed payload rejected',
        'near-zenith rectangle rejected', '24-hour bound enforced', 'legacy sampled scope preserved'],
        'scope': 'Deployed API behavior; numerical search is not temporal completeness proof'}
    (api.ROOT / '.local/rectangular-illumination-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
