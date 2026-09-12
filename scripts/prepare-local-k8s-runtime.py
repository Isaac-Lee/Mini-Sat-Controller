#!/usr/bin/env python3
"""Private local-kind runtime config; shares the existing local infrastructure for replica tests."""
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, default=ROOT / '.local/service-images.json')
    parser.add_argument('--namespace', default='msc')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/k8s/runtime.json')
    args = parser.parse_args()
    source = dict(line.split('=', 1) for line in (ROOT / '.local/msa.env').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
    images = json.loads(args.images.read_text())
    common = {key: source[key] for key in ('MSC_TIME_SOURCE', 'MSC_TIME_OFFSET_SECONDS',
                                         'MSC_TIME_VALID_FROM', 'MSC_TIME_VALID_UNTIL')}
    common.update({'MSC_SECURITY_MODE': 'local', 'MSC_RABBIT_HOST': 'host.docker.internal',
                   'MSC_RABBIT_PORT': '55672', 'MSC_DATABASE_POOL_SIZE': '2',
                   'MSC_S3_ENDPOINT': 'http://host.docker.internal:59000',
                   'MSC_OREKIT_ARCHIVE': '/orekit/time-frames.zip',
                   'MSC_OREKIT_SHA256': 'ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1'})
    items = [{'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': args.namespace}},
             {'apiVersion': 'v1', 'kind': 'ConfigMap',
              'metadata': {'name': 'msc-runtime', 'namespace': args.namespace}, 'data': common}]
    for service in images:
        db_user = 'msc_' + service.replace('-', '_')
        credentials = {key: source[key] for key in (
            'MSC_RABBIT_USER', 'MSC_RABBIT_PASSWORD', 'MSC_LOCAL_ADMIN_PASSWORD',
            'MSC_LOCAL_OPERATOR_PASSWORD', 'MSC_LOCAL_REQUESTER_PASSWORD', 'MSC_SERVICE_PASSWORD')}
        credentials.update({'MSC_DATABASE_URL': f'jdbc:postgresql://host.docker.internal:55432/{db_user}',
                            'MSC_DATABASE_USER': db_user,
                            'MSC_DATABASE_PASSWORD': source['MSC_DB_'+service.upper().replace('-', '_')+'_PASSWORD']})
        if service in ('flight-dynamics', 'simulator'):
            credentials.update({key: source[key] for key in ('MSC_S3_ACCESS_KEY', 'MSC_S3_SECRET_KEY')})
        items.append({'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque',
                      'metadata': {'name': f'msc-{service}-runtime', 'namespace': args.namespace},
                      'stringData': credentials})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as out:
        os.fchmod(out.fileno(), 0o600)
        json.dump({'apiVersion': 'v1', 'kind': 'List', 'items': items}, out)
    print(f'Private local runtime resources written for {len(images)} services; no credential values printed')


if __name__ == '__main__':
    main()
