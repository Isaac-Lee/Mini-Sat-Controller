#!/usr/bin/env python3
"""Fetch a pinned minimal Orekit UTC/EOP snapshot, verify Git blobs, and atomically package it.

Operational bootstrap tooling only; flight calculations remain in Java/Orekit.
"""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '3e376b326373467647b1e246ebb083cd9e57cd68'
FILES = {
    'tai-utc.dat': '89d16226f380b98240d9e47de4a845396d3c0ac0',
    'Earth-Orientation-Parameters/IAU-2000/finals2000A.all': 'c366ae70e6a39cd121c1f2a8b5e0e97d0a6c5522',
}
TARGET = ROOT / '.local' / 'orekit' / COMMIT
TARGET.mkdir(parents=True, exist_ok=True)


def git_blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def main():
    records = []
    for name, blob in FILES.items():
        path = TARGET / name
        url = f'https://gitlab.orekit.org/orekit/orekit-data/-/raw/{COMMIT}/{name}'
        if not path.exists() or git_blob(path.read_bytes()) != blob:
            print(f'Fetching {name}', flush=True)
            with urllib.request.urlopen(url, timeout=300) as response:
                data = response.read(10_000_001)
            if len(data) > 10_000_000 or git_blob(data) != blob:
                raise RuntimeError(f'Official file integrity mismatch: {name}')
            path.parent.mkdir(parents=True, exist_ok=True)
            partial = path.with_suffix(path.suffix + '.part')
            partial.write_bytes(data)
            partial.replace(path)
        data = path.read_bytes()
        records.append({'path': name, 'url': url, 'gitBlob': blob,
                        'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    archive = TARGET / 'time-frames.zip'
    temporary = archive.with_suffix('.part')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_STORED) as output:
        for name in sorted(FILES):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            output.writestr(info, (TARGET / name).read_bytes())
    temporary.replace(archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest = {'sourceCommit': COMMIT, 'scope': 'UTC history and IAU-2000 EOP only',
                'archive': str(archive), 'sha256': digest, 'files': records}
    (TARGET / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'archive': str(archive), 'sha256': digest}))


if __name__ == '__main__':
    main()
