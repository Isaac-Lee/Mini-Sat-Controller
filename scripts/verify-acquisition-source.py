#!/usr/bin/env python3
"""Verify automatic RabbitMQ intake and actual Acquisition-owned MinIO bytes after downlink."""
import hashlib
import importlib
import json
import subprocess
import time
import urllib.parse
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    fixture = json.loads((api.ROOT / '.local/simulation-downlink-verification.json').read_text())
    identity = fixture['planId']
    path = '/api/acquisition/simulation-sources/' + identity
    until = time.monotonic() + 90
    while time.monotonic() < until:
        work = api.call(8111, 'GET', path + '/work', expected=[200, 404])
        if work.get('status') == 'STORED':
            break
        assert work.get('status') != 'REJECTED', work
        time.sleep(.5)
    else:
        raise AssertionError(work)
    saved = api.call(8111, 'GET', path)
    body = saved['body']
    receipt = api.call(8114, 'GET', '/internal/simulation/downlinks/' + identity + '/receipt')
    assert body['receipt'] == receipt
    canonical = json.dumps(receipt, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    assert body['receiptSha256'] == hashlib.sha256(canonical.encode()).hexdigest()
    assert body['environment'] == 'SIMULATION' and body['status'] == 'RAW_SOURCE_STORED'
    assert body['byteCount'] == fixture['receipt']['byteCount']
    assert body['sha256'] == fixture['receipt']['sha256']
    reference = urllib.parse.urlsplit(body['objectReference'])
    assert reference.scheme == 's3' and reference.netloc == 'msc-acquisition'
    assert body['objectReference'] != fixture['receipt']['objectReference']
    # curl receives signing credentials on stdin, never in command arguments or output.
    config = '\n'.join([
        'url = ' + json.dumps('http://127.0.0.1:59000/' + reference.netloc + reference.path),
        'aws-sigv4 = "aws:amz:us-east-1:s3"',
        'user = ' + json.dumps(api.ENV['MSC_S3_ACCESS_KEY'] + ':' + api.ENV['MSC_S3_SECRET_KEY']),
        'fail', 'silent', 'show-error', 'max-time = 30'])
    response = subprocess.run(['curl', '--config', '-'], input=config.encode(), capture_output=True)
    assert response.returncode == 0, 'Owned MinIO object read failed'
    assert len(response.stdout) == body['byteCount']
    assert hashlib.sha256(response.stdout).hexdigest() == body['sha256']
    key = 'verify-source-' + uuid.uuid4().hex
    imported = api.call(8111, 'POST', '/api/acquisition/simulation-sources', {'receiptId': identity}, key)
    assert imported == saved
    assert api.call(8111, 'POST', '/api/acquisition/simulation-sources', {'receiptId': identity}, key) == saved
    api.call(8111, 'GET', path, user='requester', expected=403)
    api.call(8111, 'GET', path + '/work', user='requester', expected=403)
    report = {'receiptId': identity, 'byteCount': body['byteCount'], 'sha256': body['sha256'],
              'objectReference': body['objectReference'], 'attempts': work['attempts'],
              'checks': ['automatic RabbitMQ intake and worker completion', 'exact owner receipt and canonical hash',
                         'separate Acquisition bucket', 'actual MinIO byte count and SHA-256',
                         'manual import and idempotent replay retain automatic source', 'requester denied'],
              'scope': 'Synthetic raw source import; not L0, product, or fulfillment'}
    (api.ROOT / '.local/acquisition-source-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
