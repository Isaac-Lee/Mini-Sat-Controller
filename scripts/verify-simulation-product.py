#!/usr/bin/env python3
"""Verify automatic Product generation and real Product-owned MinIO source/index objects."""
import hashlib
import importlib
import json
import subprocess
import time
import urllib.parse
import uuid

api = importlib.import_module('verify-public-orbit')


def read_owned(reference):
    target = urllib.parse.urlsplit(reference)
    assert target.scheme == 's3' and target.netloc == 'msc-product'
    config = '\n'.join([
        'url = ' + json.dumps('http://127.0.0.1:59000/' + target.netloc + target.path),
        'aws-sigv4 = "aws:amz:us-east-1:s3"',
        'user = ' + json.dumps(api.ENV['MSC_S3_ACCESS_KEY'] + ':' + api.ENV['MSC_S3_SECRET_KEY']),
        'fail', 'silent', 'show-error', 'max-time = 30'])
    response = subprocess.run(['curl', '--config', '-'], input=config.encode(), capture_output=True)
    assert response.returncode == 0, 'Product-owned object read failed'
    assert reference.endswith('/' + hashlib.sha256(response.stdout).hexdigest())
    return response.stdout


def main():
    fixture = json.loads((api.ROOT / '.local/acquisition-manifest-verification.json').read_text())
    identity = fixture['manifestId']
    path = '/api/products/simulation-source-packages/' + identity
    until = time.monotonic() + 120
    while time.monotonic() < until:
        work = api.call(8112, 'GET', path + '/work', expected=[200, 404])
        if work.get('status') == 'STORED':
            break
        assert work.get('status') != 'REJECTED', work
        time.sleep(.5)
    else:
        raise AssertionError(work)
    saved = api.call(8112, 'GET', path)
    body = saved['body']
    manifest = api.call(8111, 'GET', '/api/acquisition/simulation-manifests/' + identity)
    assert body['acquisitionManifest'] == manifest
    canonical = json.dumps(manifest, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    assert body['acquisitionManifestSha256'] == hashlib.sha256(canonical.encode()).hexdigest()
    assert body['environment'] == 'SIMULATION' and body['status'] == 'SOURCE_PACKAGE_STORED'
    assert body['format'] == 'MSC_SIMULATED_SOURCE_PACKAGE_V1'
    assert len(body['sources']) == len(manifest['body']['expected'])
    total = 0
    for source in body['sources']:
        original = manifest['body']['received'][source['receiptId']]['body']
        assert source['sha256'] == original['sha256'] and source['byteCount'] == original['byteCount']
        assert source['objectReference'] != original['objectReference']
        raw = read_owned(source['objectReference'])
        assert len(raw) == source['byteCount'] and hashlib.sha256(raw).hexdigest() == source['sha256']
        total += len(raw)
    assert total == body['byteCount'] == manifest['body']['receivedBytes']
    index = json.loads(read_owned(body['manifestObjectReference']))
    assert index['sources'] == body['sources'] and index['acquisitionManifest'] == manifest
    assert index['quality'] == {'wholeSourceCompleteness': 'COMPLETE', 'packetCompleteness': 'NOT_ASSESSED', 'sensorQualification': 'NOT_ESTABLISHED'}
    key = 'verify-product-' + uuid.uuid4().hex
    assert api.call(8112, 'POST', '/api/products/simulation-source-packages', {'manifestId': identity}, key) == saved
    assert api.call(8112, 'POST', '/api/products/simulation-source-packages', {'manifestId': identity}, key) == saved
    api.call(8112, 'GET', path, user='requester', expected=403)
    api.call(8112, 'GET', path + '/work', user='requester', expected=403)
    report = {'productId': identity, 'sourceCount': len(body['sources']), 'byteCount': total, 'attempts': work['attempts'],
              'manifestObjectReference': body['manifestObjectReference'],
              'checks': ['automatic RabbitMQ Product job', 'exact Acquisition manifest binding',
                         'actual Product-owned source bytes and hashes', 'actual package index and quality limits',
                         'manual/replayed request retains automatic product', 'requester denied'],
              'scope': 'Lossless synthetic source package; not qualified L0, quicklook or fulfillment'}
    (api.ROOT / '.local/simulation-product-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
