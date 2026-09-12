#!/usr/bin/env python3
"""Requires tasking processes on 8101 and 18101 sharing their owned database and queue."""
import concurrent.futures
import json
from pathlib import Path
import runpy
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
helper = runpy.run_path(str(ROOT/'scripts/verify-tasking.py'))
call, publish, await_status = helper['call'], helper['publish'], helper['await_status']


def main():
    for port in (8101, 18101):
        assert call(port, 'GET', '/actuator/health/readiness', user=None)['status'] == 'UP'
    key = str(uuid.uuid4())
    area = {'id': 'sim-replica-area-'+key, 'west': 127.3, 'south': 36.3, 'east': 127.5, 'north': 36.5, 'sourceReference': 'synthetic replication fixture'}
    body = {'target': 'Synthetic replica verification', 'area': area}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        submitted = list(pool.map(lambda port: call(port, 'POST', '/api/requests', body, key), (8101, 18101)))
    assert submitted[0] == submitted[1]
    request = submitted[0]
    path = '/api/requests/'+request['id']
    def revise(port):
        try:
            result = call(port, 'PUT', path, {'expectedVersion': request['version'], 'submission': {**body, 'target': 'Revision from '+str(port)}}, key+str(port))
            return result['version']
        except AssertionError as error:
            assert error.args[0][1] == 409, error
            return 'conflict'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        revisions = list(pool.map(revise, (8101, 18101)))
    assert sorted(map(str, revisions)) == ['2', 'conflict'], revisions
    updated = call(18101, 'GET', path)
    assert updated['body']['request']['revision'] == 2
    cancelled = call(18101, 'POST', path+'/cancel', {'expectedVersion': updated['version']}, key+'cancel')
    event_id = publish('ScheduleAssignmentCommitted', {'requestId': request['id'], 'revision': 2, 'evidenceReference': 'synthetic delayed schedule after cancellation'})
    assert call(8101, 'GET', path) == cancelled
    assert call(18101, 'GET', '/api/admin/delivery/inbox/'+event_id, user='admin')['received']
    deadline = {'seconds': int(time.time())+int(helper['ENV']['MSC_TIME_OFFSET_SECONDS'])+3, 'nanos': 0, 'scale': 'TAI'}
    expiring = call(18101, 'POST', '/api/requests', {**body, 'deadline': deadline}, key+'expiry')
    expired = await_status(expiring['id'], 'EXPIRED')
    assert expired['version'] == 2 and expired['body']['request']['revision'] == 2
    result = {'passed': ['cross-process idempotent creation', 'one winner for concurrent specification revision', 'shared transactional inbox', 'late schedule cannot revive cancellation', 'expiry has a single durable transition'], 'requestId': request['id'], 'ports': [8101, 18101], 'scope': 'Two local JVMs; Kubernetes scaling is not yet verified'}
    (ROOT/'.local/tasking-replica-verification.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
