#!/usr/bin/env python3
"""Build independently tagged service images using a JAR-only Docker context."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def services():
    tree = ET.parse(ROOT / 'pom.xml')
    return [m.text.removeprefix('services/msc-').removesuffix('-service')
            for m in tree.findall('.//{*}modules/{*}module') if m.text.startswith('services/msc-')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, help='Java 21 runtime image pinned with @sha256:')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/service-images.json')
    parser.add_argument('--service', choices=services(), action='append')
    args = parser.parse_args()
    if '@sha256:' not in args.runtime:
        parser.error('Runtime must be pinned by digest')
    manifest = {}
    for service in args.service or services():
        candidates = list((ROOT / f'services/msc-{service}-service/target').glob('*.jar'))
        if len(candidates) != 1:
            raise RuntimeError(f'Build the reactor first: expected one executable JAR for {service}')
        jar = candidates[0]
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        tag_digest = hashlib.sha256((digest + args.runtime).encode()).hexdigest()[:24]
        image = f'msc-{service}:{tag_digest}'
        with tempfile.TemporaryDirectory(prefix='msc-image-') as work:
            context = Path(work)
            shutil.copyfile(jar, context / 'app.jar')
            shutil.copyfile(ROOT / 'deploy/Dockerfile.service', context / 'Dockerfile')
            subprocess.run(['docker', 'build', '--build-arg', f'JAVA_RUNTIME={args.runtime}',
                            '--label', f'msc.jar.sha256={digest}', '-t', image, str(context)], check=True)
        manifest[service] = {'image': image, 'jarSha256': digest, 'runtime': args.runtime}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2)+'\n')
    print(f'Built {len(manifest)} independent images; manifest: {args.output}')


if __name__ == '__main__':
    main()
