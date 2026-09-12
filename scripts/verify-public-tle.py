#!/usr/bin/env python3
"""Verify deployed derived TLE for the preserved SPACEEYE-T1 GP snapshot."""
import base64
import hashlib
import importlib
import json
import os
import urllib.request

api = importlib.import_module('verify-public-orbit')


def main():
    snapshot = api.call(8105, 'GET', '/api/tracked-satellites/63229/orbit')
    path = '/api/tracked-satellites/63229/tle'
    exported = api.call(8103, 'GET', path, user='requester')
    assert exported['noradId'] == 63229 and exported['name'] == snapshot['elements']['name']
    assert exported['snapshotId'] == snapshot['id']
    assert exported['sourceRawSha256'] == hashlib.sha256(snapshot['rawJson'].encode()).hexdigest()
    assert exported['epochUtc'] == snapshot['elements']['epochUtc']
    assert exported['fetchedAt'] == snapshot['fetchedAt']
    assert exported['representation'] == 'DERIVED_FROM_GP_WITH_TLE_PRECISION_ROUNDING_NOT_TELEMETRY'
    for number, key in [(1, 'line1'), (2, 'line2')]:
        line = exported[key]
        assert len(line) == 69 and line.startswith(str(number) + ' 63229')
        checksum = sum(int(c) if c.isdigit() else 1 if c == '-' else 0 for c in line[:68]) % 10
        assert checksum == int(line[68])
    port = os.environ.get('MSC_VERIFY_PORT_8103', '8103')
    request = urllib.request.Request('http://127.0.0.1:' + port + path + '.txt')
    request.add_header('Authorization', 'Basic ' + base64.b64encode(('service:' + api.ENV['MSC_SERVICE_PASSWORD']).encode()).decode())
    with urllib.request.urlopen(request, timeout=30) as response:
        text = response.read().decode()
        assert response.headers['X-MSC-Orbit-Source'] == 'PUBLIC_GP_DERIVED_TLE'
        assert response.headers['X-MSC-Snapshot-Id'] == snapshot['id']
    assert text == exported['line1'] + '\n' + exported['line2'] + '\n'
    api.call(8103, 'GET', '/api/tracked-satellites/100000/tle', expected=400)
    assert api.call(8105, 'GET', '/api/orbit-references/' + snapshot['id']) == snapshot
    output = api.ROOT / '.local/spaceeye-t1-derived.tle'
    output.write_text(text)
    report = {'noradId': 63229, 'snapshotId': snapshot['id'], 'epochUtc': exported['epochUtc'],
              'checks': ['actual preserved GP binding', 'two 69-character lines and NORAD identity',
                         'independently computed checksums', 'authenticated requester read',
                         'exact text download and provenance headers', 'field-range rejection and original GP preservation'],
              'scope': 'Derived rounded TLE, not provider-original TLE or spacecraft telemetry'}
    (api.ROOT / '.local/public-tle-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
