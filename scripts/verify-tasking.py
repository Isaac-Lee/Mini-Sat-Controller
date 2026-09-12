#!/usr/bin/env python3
"""Exercise local tasking/reference processes and RabbitMQ. Injected quality is synthetic test evidence."""
import base64
import json
import time
import uuid
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV = dict(line.split('=', 1) for line in (ROOT/'.local/msa.env').read_text().splitlines() if '=' in line and not line.startswith('#'))


def call(port, method, path, body=None, key=None, user='requester', expected=200):
    request = urllib.request.Request(f'http://127.0.0.1:{port}'+path, data=None if body is None else json.dumps(body).encode(), method=method)
    if user:
        names = {'requester': 'MSC_LOCAL_REQUESTER_PASSWORD', 'admin': 'MSC_LOCAL_ADMIN_PASSWORD', 'service': 'MSC_SERVICE_PASSWORD', 'broker': 'MSC_RABBIT_PASSWORD'}
        login = ENV['MSC_RABBIT_USER'] if user == 'broker' else user
        request.add_header('Authorization', 'Basic '+base64.b64encode((login+':'+ENV[names[user]]).encode()).decode())
    request.add_header('Content-Type', 'application/json')
    if key:
        request.add_header('Idempotency-Key', key)
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        assert response.status == expected, (path, response.status, raw[:500])
        return json.loads(raw) if raw else None


def await_status(request_id, expected):
    until = time.monotonic()+15
    while time.monotonic() < until:
        state = call(8101, 'GET', '/api/requests/'+request_id)
        if state['body']['request']['status'] == expected:
            return state
        time.sleep(0.15)
    raise AssertionError(('Expected request status', expected, state))


def publish(kind, payload, event_id=None):
    event_id = event_id or str(uuid.uuid4())
    event = {'eventId': event_id, 'schemaVersion': 1, 'type': kind, 'aggregateId': payload['requestId'], 'aggregateVersion': payload['revision'], 'correlationId': str(uuid.uuid4()), 'causationId': None,
             'occurredAt': {'seconds': int(time.time())+int(ENV['MSC_TIME_OFFSET_SECONDS']), 'nanos': 0, 'scale': 'TAI'}, 'payload': payload}
    response = call(55673, 'POST', '/api/exchanges/%2F/msc.events.v1/publish', {'properties': {'delivery_mode': 2, 'content_type': 'application/json'}, 'routing_key': kind, 'payload': json.dumps(event), 'payload_encoding': 'string'}, user='broker')
    assert response['routed']
    until = time.monotonic()+15
    while time.monotonic() < until:
        if call(8101, 'GET', '/api/admin/delivery/inbox/'+event_id, user='admin')['received']:
            return event_id
        time.sleep(0.15)
    raise AssertionError(('Event did not commit to inbox', event_id, kind))


def main():
    run = uuid.uuid4().hex
    for port in (8101, 8105):
        assert call(port, 'GET', '/actuator/health/readiness', user=None)['status'] == 'UP'
    call(8101, 'GET', '/api/requests', user=None, expected=401)
    call(8101, 'POST', '/api/requests', {'target': run, 'owner': 'admin'}, run+'spoof', expected=400)
    alias = 'simulation city '+run
    place_id = 'verify-'+run
    area = {'id': place_id+':1', 'west': 127.3, 'south': 36.3, 'east': 127.5, 'north': 36.5, 'sourceReference': 'explicit-simulation-geography-fixture'}
    place = {'id': place_id, 'version': 1, 'aliases': [alias, 'ambiguous-'+run], 'area': area, 'approvalReference': 'local-verifier-admin-fixture'}
    call(8105, 'POST', '/api/places', place, run, expected=403)
    first = call(8105, 'POST', '/api/places', place, run, user='admin')
    assert call(8105, 'POST', '/api/places', place, run, user='admin') == first
    request = call(8101, 'POST', '/api/requests', {'target': '  SIMULATION   CITY '+run.upper()+'  '}, run)
    request_id = request['id']
    assert request['body']['request']['resolvedAoi'] is None
    accepted = await_status(request_id, 'ACCEPTED')
    assert accepted['body']['area']['id'] == area['id']
    assert call(8101, 'POST', '/api/requests', {'target': '  SIMULATION   CITY '+run.upper()+'  '}, run) == request
    assert accepted['body']['criteria'] == {'minimumCoverageFraction': 1.0, 'maximumCloudFraction': 1.0}
    second_area = {**area, 'id': 'other-'+run+':1'}
    call(8105, 'POST', '/api/places', {'id': 'other-'+run, 'version': 1, 'aliases': ['ambiguous-'+run], 'area': second_area, 'approvalReference': 'synthetic homonym'}, run+'homonym', user='admin')
    ambiguous = call(8101, 'POST', '/api/requests', {'target': 'ambiguous-'+run}, run+'ambiguous')
    pending = await_status(ambiguous['id'], 'CLARIFICATION_NEEDED')
    assert pending['body']['request']['resolvedAoi'] is None and len(pending['body']['clarificationOptions']) == 2
    revised = call(8101, 'PUT', '/api/requests/'+pending['id'], {'expectedVersion': pending['version'], 'submission': {'target': alias, 'area': area}}, run+'revision')
    assert revised['body']['request']['revision'] == 2 and revised['body']['request']['status'] == 'ACCEPTED'
    publish('TargetResolved', {'requestId': pending['id'], 'revision': 1, 'area': second_area, 'reference': 'stale-simulation-result', 'reason': 'Delayed old resolution', 'candidates': [second_area]})
    assert call(8101, 'GET', '/api/requests/'+pending['id'])['body']['area']['id'] == area['id']
    version_two = {**place, 'version': 2, 'area': {**area, 'id': place_id+':2', 'east': 127.51}}
    call(8105, 'POST', '/api/places', version_two, run+'place-v2', user='admin')
    assert call(8101, 'GET', '/api/requests/'+request_id)['body']['area']['id'] == area['id']
    newer = call(8101, 'POST', '/api/requests', {'target': alias}, run+'newer')
    assert await_status(newer['id'], 'ACCEPTED')['body']['area']['id'] == place_id+':2'
    admin_request = call(8101, 'POST', '/api/requests', {'target': alias, 'area': area}, run+'admin', user='admin')
    call(8101, 'GET', '/api/requests/'+admin_request['id'], expected=404)
    call(8101, 'POST', '/api/requests/'+admin_request['id']+'/cancel', {'expectedVersion': 1}, run+'forbidden-cancel', expected=404)
    page = call(8101, 'GET', '/api/requests?limit=1')
    assert len(page['items']) == 1 and page['nextCursor']
    next_page = call(8101, 'GET', '/api/requests?limit=1&after='+page['nextCursor'])
    assert next_page['items'][0]['id'] != page['items'][0]['id']
    assert all(row['body']['owner'] == 'requester' for row in page['items']+next_page['items'])
    cancel = call(8101, 'POST', '/api/requests/'+newer['id']+'/cancel', {'expectedVersion': 2}, run+'cancel')
    assert cancel['body']['request']['status'] == 'CANCELLED' and cancel['body']['request']['revision'] == 2
    publish('ScheduleAssignmentCommitted', {'requestId': newer['id'], 'revision': 1, 'evidenceReference': 'delayed synthetic schedule'})
    assert call(8101, 'GET', '/api/requests/'+newer['id'])['body']['request']['status'] == 'CANCELLED'
    quality = call(8101, 'POST', '/api/requests', {'target': alias, 'area': area, 'criteria': {'minimumCoverageFraction': .9, 'maximumCloudFraction': .2}}, run+'quality')
    assert quality['body']['area']['id'].startswith('request-area:')
    assert quality['body']['area']['id'] != area['id']
    original = call(8101, 'GET', '/api/requests/'+quality['id']+'/submissions/1')
    assert original['area'] == area
    evidence = {'requestId': quality['id'], 'revision': 1, 'productReference': 'simulation-only-product', 'acquisitionReference': 'simulation-only-acquisition', 'unionCoverageFraction': .5, 'cloudFraction': .1, 'assessmentReference': 'synthetic-quality-1'}
    event_id = publish('ProductQualityAssessed', evidence)
    partial = await_status(quality['id'], 'PARTIALLY_FULFILLED')
    publish('ProductQualityAssessed', evidence, event_id)
    publish('PlanningRejected', {'requestId': quality['id'], 'revision': 1, 'evidenceReference': 'synthetic-inbox-barrier'})
    assert call(8101, 'GET', '/api/requests/'+quality['id'])['version'] == partial['version']+1
    publish('ProductQualityAssessed', {**evidence, 'unionCoverageFraction': 1, 'cloudFraction': .8, 'assessmentReference': 'synthetic-cloud-failure'})
    assert call(8101, 'GET', '/api/requests/'+quality['id'])['body']['request']['status'] == 'PARTIALLY_FULFILLED'
    publish('ProductQualityAssessed', {**evidence, 'unionCoverageFraction': .95, 'assessmentReference': 'synthetic-quality-complete'})
    await_status(quality['id'], 'FULFILLED')
    deadline = {'seconds': int(time.time())+int(ENV['MSC_TIME_OFFSET_SECONDS'])+3, 'nanos': 0, 'scale': 'TAI'}
    expiring = call(8101, 'POST', '/api/requests', {'target': alias, 'area': area, 'deadline': deadline}, run+'expiry')
    expired = await_status(expiring['id'], 'EXPIRED')
    assert expired['body']['request']['revision'] == 2
    result = {'passed': ['real cross-service broker resolution', 'unknown AOI represented honestly', 'homonym clarification', 'pinned gazetteer versions', 'owner isolation and pagination', 'revisions and stale event suppression', 'cancellation and expiry', 'inbox duplicate suppression', 'quality threshold transitions'],
              'requestId': request_id, 'pinnedAreaId': area['id'], 'syntheticQualityEvidence': True}
    (ROOT/'.local/tasking-verification.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
