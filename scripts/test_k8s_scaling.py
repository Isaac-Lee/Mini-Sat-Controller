#!/usr/bin/env python3
"""Offline checks for render-k8s.py HPA/metrics-server output and load-smoke.py measurement logic."""
import http.server
import importlib
import json
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
render_k8s = importlib.import_module('render-k8s')
load_smoke = importlib.import_module('load-smoke')

SERVICES = ['acquisition', 'anomaly', 'flight-dynamics', 'ground-operations', 'mission-definition',
            'monitoring', 'planning', 'product', 'reference-data', 'simulator', 'spacecraft-control', 'tasking']
IMAGES = {name: {'image': f'msc/{name}@sha256:0', 'jarSha256': '0'} for name in SERVICES}
VOLUME = {'persistentVolumeClaim': {'claimName': 'msc-orekit'}}


def kinds(manifest, kind):
    return {item['metadata']['name']: item for item in manifest['items'] if item['kind'] == kind}


class RenderTest(unittest.TestCase):
    def test_default_render_is_unchanged_by_hpa_support(self):
        manifest = render_k8s.render(IMAGES, 'msc', VOLUME)
        self.assertEqual(kinds(manifest, 'HorizontalPodAutoscaler'), {})
        for deployment in kinds(manifest, 'Deployment').values():
            self.assertEqual(deployment['spec']['replicas'], 1)
            env = deployment['spec']['template']['spec']['containers'][0]['env']
            self.assertNotIn('MSC_DATABASE_POOL_SIZE', [e['name'] for e in env])

    def test_hpa_only_for_replica_verified_services_within_budget(self):
        manifest = render_k8s.render(IMAGES, 'msc', VOLUME, hpa_pool_size=2)
        hpas = kinds(manifest, 'HorizontalPodAutoscaler')
        self.assertEqual(set(hpas), render_k8s.HPA_SERVICES)
        deployments = kinds(manifest, 'Deployment')
        for name, hpa in hpas.items():
            spec = hpa['spec']
            self.assertEqual((spec['minReplicas'], spec['maxReplicas']), (1, 2))
            self.assertEqual(spec['scaleTargetRef']['name'], name)
            self.assertEqual(spec['metrics'][0]['resource']['name'], 'cpu')
            self.assertNotIn('replicas', deployments[name]['spec'])
        for name, deployment in deployments.items():
            container = deployment['spec']['template']['spec']['containers'][0]
            self.assertIn({'name': 'MSC_DATABASE_POOL_SIZE', 'value': '2'}, container['env'])
            self.assertEqual(container['resources']['requests'], {'cpu': '250m', 'memory': '256Mi'})
            if name not in hpas:
                self.assertEqual(deployment['spec']['replicas'], 1)
        # 9 fixed + 3 HPA services at 2 Pods + 12 surge Pods (every Deployment rolling) = 27 Pods.
        self.assertEqual(render_k8s.database_connections(IMAGES, 2), 54)
        self.assertEqual(render_k8s.database_connections(IMAGES, 6), 162)

    def test_cli_rejects_unbudgeted_pool(self):
        with tempfile.NamedTemporaryFile('w', suffix='.json') as images:
            json.dump(IMAGES, images)
            images.flush()
            cli = lambda *extra: subprocess.run(
                [sys.executable, str(Path(__file__).parent / 'render-k8s.py'), '--images', images.name, *extra],
                capture_output=True, text=True)
            self.assertIn('exceed', cli('--hpa', '--database-pool-size', '6').stderr)
            self.assertIn('at least 1', cli('--hpa', '--database-pool-size', '0').stderr)
            self.assertEqual(cli('--hpa', '--database-pool-size', '2').returncode, 0)

    def test_metrics_server_is_marked_local_only(self):
        items = render_k8s.local_metrics_server()
        self.assertTrue(all(item['metadata']['labels']['msc.local-only'] == 'true' for item in items))
        deployment = next(item for item in items if item['kind'] == 'Deployment')
        self.assertIn('--kubelet-insecure-tls', deployment['spec']['template']['spec']['containers'][0]['args'])


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(500 if self.path == '/fail' else 200)
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *args):
        pass


class LoadSmokeTest(unittest.TestCase):
    def test_percentile_nearest_rank(self):
        values = list(range(1, 101))
        self.assertEqual(load_smoke.percentile(values, 50), 50)
        self.assertEqual(load_smoke.percentile(values, 99), 99)
        self.assertEqual(load_smoke.percentile([7], 90), 7)

    def test_parse_top(self):
        self.assertEqual(load_smoke.parse_top('planning-abc   12m   301Mi\ntasking-x 1   1Gi\n'),
                         {'planning-abc': {'cpuMillicores': 12, 'memoryMi': 301},
                          'tasking-x': {'cpuMillicores': 1000, 'memoryMi': 1024}})
        self.assertEqual(load_smoke.parse_top('a 0m 2048Ki')['a']['memoryMi'], 2)

    def test_default_targets_use_a_permitted_user(self):
        users = {path: load_smoke.user_for(path) for _, _, path in load_smoke.DEFAULT_TARGETS}
        self.assertEqual(users['/internal/missions'], 'service')
        self.assertEqual(users['/api/catalog?limit=20'], 'admin')

    def test_run_counts_errors_deterministically(self):
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            port = server.server_address[1]
            targets = [('ok', port, '/ok'), ('bad', port, '/fail')]
            result = load_smoke.run(targets, requests=10, concurrency=3, timeout=5, authorizations={'admin': 'Basic x', 'service': 'Basic y'})
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(result['ok']['requests'], 5)
        self.assertEqual(result['ok']['errorRate'], 0)
        self.assertEqual(result['bad']['errors'], 5)
        self.assertEqual(result['all']['errorRate'], 0.5)


if __name__ == '__main__':
    unittest.main()
