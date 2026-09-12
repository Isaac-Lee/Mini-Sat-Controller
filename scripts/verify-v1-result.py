"""Complete the synthetic request and verify requester-owned downloads through Tasking."""
import base64
import hashlib
import importlib
import os
import urllib.request


def execute(call, request_id, execution, key):
    api = importlib.import_module('verify-public-orbit')
    base = '/api/requests/' + request_id
    downlink = execution['downlink']
    current = call(8101, 'GET', base, user='requester')
    body = {'expectedVersion': current['version'], 'productId': downlink['productId'],
            'imageLoadId': execution['releasedLoadId'], 'downlinkLoadId': downlink['loadId'],
            'reviewReference': 'V1 synthetic workflow review; physical image quality not assessed'}
    path = base + '/simulation-result'
    call(8101, 'POST', path, body, key + '-result-denied', 'requester', 403)
    saved = call(8101, 'POST', path, body, key + '-result', 'operator1')
    assert call(8101, 'POST', path, body, key + '-result', 'operator1') == saved
    assert call(8101, 'GET', path, user='requester') == saved
    request = call(8101, 'GET', base, user='requester')
    assert request['body']['request']['status'] == 'FULFILLED'
    assert 'SIMULATION_V1_COMPLETE' in request['body']['reason']
    result = saved['body']
    assert result['requestId'] == request_id and result['environment'] == 'SIMULATION'
    assert result['status'] == 'COMPLETE' and result['scenarioId'] == execution['scenarioId']
    source = result['sources'][0]
    assert source['sha256'] == execution['payloadSha256'] == downlink['sourceSha256']
    for field, count, digest in [('contentPath', 'byteCount', 'sha256'),
                                 ('previewPath', 'previewByteCount', 'previewSha256')]:
        port = os.environ.get('MSC_VERIFY_PORT_8101', '8101')
        download = urllib.request.Request('http://127.0.0.1:' + port + source[field])
        download.add_header('Authorization', 'Basic ' + base64.b64encode(
            ('requester:' + api.ENV['MSC_LOCAL_REQUESTER_PASSWORD']).encode()).decode())
        with urllib.request.urlopen(download, timeout=60) as response:
            assert response.headers['X-MSC-Environment'] == 'SIMULATION'
            raw = response.read()
            assert len(raw) == source[count] and hashlib.sha256(raw).hexdigest() == source[digest]
        if field == 'previewPath':
            assert raw.startswith(b'\x89PNG\r\n\x1a\n')
            (api.ROOT / '.local/v1-request-preview.png').write_bytes(raw)
    return {'requestStatus': 'FULFILLED', 'result': result,
            'checks': ['operator-only completion', 'idempotent completion', 'requester result lookup',
                       'requester raw and PNG downloads with exact byte count and SHA-256'],
            'scope': 'Synthetic functional completion; physical image quality not assessed'}
