#!/usr/bin/env python3
"""Read real S3-backed onboard payload after verify-simulation-command.py creates an IMAGE."""
import base64
from decimal import Decimal
import hashlib
import importlib
import json
import os
import time
import urllib.request

api = importlib.import_module('verify-public-orbit')


def main():
    fixture = json.loads((api.ROOT / '.local/simulation-command-restart-baseline.json').read_text())
    scenario = fixture['scenario']['id']
    entry = fixture['ledger']['body']['entries'][0]
    command = entry['command']['id']['value']
    identity = hashlib.sha256(json.dumps([scenario, command], separators=(',', ':')).encode()).hexdigest()
    path = '/internal/simulation/payloads/' + identity
    until = time.monotonic() + 60
    while time.monotonic() < until:
        saved = api.call(8114, 'GET', path, expected=[200, 404])
        if 'body' in saved:
            break
        time.sleep(.5)
    else:
        raise AssertionError('Payload not materialized by scheduled worker')
    manifest = saved['body']
    assert manifest['source']['scenarioId'] == scenario
    assert manifest['source']['commandId'] == command
    assert manifest['source']['catalogSha256'] == entry['catalogSha256']
    assert manifest['environment'] == 'SIMULATION' and manifest['location'] == 'ONBOARD'
    assert manifest['byteCount'] == int(Decimal(str(entry['catalog']['resources']['generatedMegabytes'])) * 1_000_000)
    api.call(8114, 'GET', path, user='requester', expected=403)
    api.call(8114, 'GET', path + '/content', user='requester', expected=403)
    port = os.environ.get('MSC_VERIFY_PORT_8114', '8114')
    request = urllib.request.Request('http://127.0.0.1:' + port + path + '/content')
    request.add_header('Authorization', 'Basic ' + base64.b64encode(('service:' + api.ENV['MSC_SERVICE_PASSWORD']).encode()).decode())
    digest, total = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=60) as response:
        assert response.headers['X-MSC-Environment'] == 'SIMULATION'
        assert response.headers['X-MSC-Location'] == 'ONBOARD'
        while block := response.read(65536):
            digest.update(block)
            total += len(block)
    assert total == manifest['byteCount'] and digest.hexdigest() == manifest['sha256']
    assert manifest['objectReference'].endswith('/' + digest.hexdigest())
    assert api.call(8114, 'GET', path) == saved
    report = {'scenarioId': scenario, 'intentId': identity, 'byteCount': total, 'sha256': digest.hexdigest(),
              'checks': ['automatic IMAGE payload materialization', 'exact command/catalog binding',
                         'declared byte count', 'real S3 stream and content hash', 'SERVICE-only metadata/content',
                         'immutable metadata read'],
              'scope': 'Synthetic onboard raw samples; no downlink or ground-reception claim'}
    (api.ROOT / '.local/simulation-payload-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
