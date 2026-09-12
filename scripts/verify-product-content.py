#!/usr/bin/env python3
"""Verify deployed product downloads and diagnostic PNG pixels against owned raw bytes."""
import base64
import hashlib
import importlib
import io
import json
import os
import urllib.request
import uuid
from PIL import Image

api = importlib.import_module('verify-public-orbit')


def binary(path):
    port = os.environ.get('MSC_VERIFY_PORT_8112', '8112')
    request = urllib.request.Request('http://127.0.0.1:' + port + path)
    request.add_header('Authorization', 'Basic ' + base64.b64encode(('service:' + api.ENV['MSC_SERVICE_PASSWORD']).encode()).decode())
    with urllib.request.urlopen(request, timeout=60) as response:
        assert response.headers['X-MSC-Environment'] == 'SIMULATION'
        assert response.headers['X-Content-Type-Options'] == 'nosniff'
        return response.read(), dict(response.headers)


def main():
    fixture = json.loads((api.ROOT / '.local/simulation-product-verification.json').read_text())
    identity = fixture['productId']
    path = '/api/products/simulation-source-packages/' + identity
    product = api.call(8112, 'GET', path)['body']
    source = product['sources'][0]
    source_path = path + '/sources/' + source['receiptId']
    raw, headers = binary(source_path + '/content')
    assert len(raw) == source['byteCount']
    assert hashlib.sha256(raw).hexdigest() == source['sha256']
    # Header casing may differ between HTTP stacks.
    assert {k.lower(): v for k, v in headers.items()}['etag'] == '"' + source['sha256'] + '"'
    index, _ = binary(path + '/index')
    assert json.loads(index)['sources'] == product['sources']
    key = 'verify-preview-' + uuid.uuid4().hex
    saved = api.call(8112, 'POST', source_path + '/preview', {}, key)
    preview = saved['body']
    assert preview['sourceSha256'] == source['sha256'] and preview['sourceByteCount'] == len(raw)
    assert preview['georeferencing'] == 'NONE'
    assert preview['validSamples'] == min(len(raw), 65536)
    png, headers = binary(source_path + '/preview/content')
    assert len(png) == preview['byteCount'] and hashlib.sha256(png).hexdigest() == preview['sha256']
    assert {k.lower(): v for k, v in headers.items()}['x-msc-purpose'] == 'SYNTHETIC_BYTE_PREVIEW_NOT_EARTH_IMAGERY'
    image = Image.open(io.BytesIO(png))
    assert image.mode == 'L' and image.size == (preview['width'], preview['height'])
    pixels = image.tobytes()
    assert pixels[:preview['validSamples']] == raw[:preview['validSamples']]
    assert all(value == 0 for value in pixels[preview['validSamples']:])
    assert api.call(8112, 'POST', source_path + '/preview', {}, key) == saved
    assert api.call(8112, 'GET', source_path + '/preview') == saved
    for denied in [path + '/index', source_path + '/content', source_path + '/preview', source_path + '/preview/content']:
        api.call(8112, 'GET', denied, user='requester', expected=403)
    api.call(8112, 'POST', source_path + '/preview', {}, key + '-denied', 'requester', 403)
    api.call(8112, 'GET', path + '/sources/' + 'f' * 64 + '/content', expected=404)
    output = api.ROOT / '.local/simulation-byte-preview.png'
    output.write_bytes(png)
    report = {'productId': identity, 'receiptId': source['receiptId'], 'sourceByteCount': len(raw),
              'previewByteCount': len(png), 'previewSha256': preview['sha256'], 'size': list(image.size),
              'checks': ['actual API source bytes and ETag', 'actual API index', 'full PNG hash and every sampled pixel',
                         'preview provenance and non-georeferenced scope', 'idempotent creation/read',
                         'requester denial and missing membership'],
              'scope': 'Diagnostic synthetic byte layout, not Earth observation imagery'}
    (api.ROOT / '.local/product-content-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
