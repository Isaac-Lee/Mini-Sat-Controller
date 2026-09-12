#!/usr/bin/env python3
"""Hooks for a real pre-reception manifest and automatic completion after downlink."""
import importlib
import json
import time

api = importlib.import_module('verify-public-orbit')


def before(scenario, plan_id, run):
    saved = api.call(8111, 'POST', '/api/acquisition/simulation-manifests',
                     {'scenarioId': scenario, 'planIds': [plan_id]}, run + '-manifest')
    assert saved['body']['completeness'] == 'INCOMPLETE'
    assert saved['body']['missingPlanIds'] == [plan_id]
    assert saved['body']['receivedBytes'] == 0
    assert saved['body']['expectedBytes'] == 1_000_000
    return saved


def after(initial, plan_id, run):
    path = '/api/acquisition/simulation-manifests/' + initial['id']
    until = time.monotonic() + 90
    while time.monotonic() < until:
        saved = api.call(8111, 'GET', path)
        if saved['body']['completeness'] == 'COMPLETE':
            break
        time.sleep(.5)
    else:
        raise AssertionError(saved)
    assert saved['version'] == initial['version'] + 1
    assert saved['body']['missingPlanIds'] == []
    assert saved['body']['receivedBytes'] == saved['body']['expectedBytes'] == 1_000_000
    source = api.call(8111, 'GET', '/api/acquisition/simulation-sources/' + plan_id)
    assert saved['body']['received'][plan_id] == source
    assert saved['body']['expected'] == initial['body']['expected']
    assert api.call(8111, 'POST', path + '/refresh', {}, run + '-refresh') == saved
    assert api.call(8111, 'POST', path + '/refresh', {}, run + '-refresh') == saved
    api.call(8111, 'GET', path, user='requester', expected=403)
    api.call(8111, 'POST', path + '/refresh', {}, run + '-denied', 'requester', 403)
    report = {'manifestId': saved['id'], 'receiptId': plan_id, 'initialVersion': initial['version'],
              'completeVersion': saved['version'], 'byteCount': saved['body']['receivedBytes'],
              'checks': ['pre-reception INCOMPLETE with missing source', 'automatic completion without refresh',
                         'exact source binding and preserved expectations', 'one completion revision',
                         'idempotent unchanged refresh', 'requester denied'],
              'scope': 'Whole synthetic source completeness; no packet, L0 or fulfillment claim'}
    (api.ROOT / '.local/acquisition-manifest-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
