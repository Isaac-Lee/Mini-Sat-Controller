#!/usr/bin/env python3
"""Bounded, deterministic read-only load on a running local MSC stack; output is local evidence only.

Request i goes to target i mod N, so the request mix is fixed for given arguments. Latency, error
rate and `kubectl top` samples describe this machine and run; they are not production capacity.
"""
import argparse
import base64
from collections import Counter
import concurrent.futures
from datetime import datetime, timezone
import http.client
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
# Read endpoints that need no ids; localhost ports from forward-local-k8s.py.
DEFAULT_TARGETS = [('tasking', 8101, '/api/requests?limit=20'),
                   ('mission-definition', 8104, '/api/catalog?limit=20'),
                   ('mission-definition', 8104, '/internal/missions'),
                   ('reference-data', 8105, '/api/tracked-satellites'),
                   ('ground-operations', 8106, '/internal/stations'),
                   ('anomaly', 8110, '/api/anomalies?limit=20')]
PASSWORDS = {'service': 'MSC_SERVICE_PASSWORD', 'admin': 'MSC_LOCAL_ADMIN_PASSWORD'}


def user_for(path):
    """SecurityConfiguration: /internal/** needs SERVICE; admin can read the /api endpoints."""
    return 'service' if path.startswith('/internal/') else 'admin'


def percentile(values, p):
    """Nearest-rank percentile of a non-empty list."""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p / 100 * len(ordered)) - 1)]


def parse_top(text):
    pods = {}
    for line in text.splitlines():
        if line.strip():
            name, cpu, memory = line.split()[:3]
            millicores = int(cpu[:-1]) if cpu.endswith('m') else int(float(cpu) * 1000)
            scale = {'Ki': 1 / 1024, 'Mi': 1, 'Gi': 1024}[memory[-2:]]
            pods[name] = {'cpuMillicores': millicores, 'memoryMi': round(int(memory[:-2]) * scale)}
    return pods


def summarize(samples):
    latencies = [latency for latency, _ in samples]
    errors = sum(1 for _, status in samples if not 200 <= status < 300)
    return {'requests': len(samples), 'errors': errors, 'errorRate': errors / len(samples),
            'statuses': dict(sorted(Counter(str(status) for _, status in samples).items())),
            'latencyMs': {f'p{p}': round(percentile(latencies, p), 2) for p in (50, 90, 99)}
            | {'max': round(max(latencies), 2)}}


def run(targets, requests, concurrency, timeout, authorizations):
    def one(index):
        label, port, path = targets[index % len(targets)]
        request = urllib.request.Request(f'http://127.0.0.1:{port}{path}')
        request.add_header('Authorization', authorizations[user_for(path)])
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
        except (OSError, http.client.HTTPException):
            status = 0  # connection failure, timeout or broken response
        return label, (time.perf_counter() - start) * 1000, status

    by_target = {}
    with concurrent.futures.ThreadPoolExecutor(concurrency) as pool:
        for label, latency, status in pool.map(one, range(requests)):
            by_target.setdefault(label, []).append((latency, status))
    result = {label: summarize(samples) for label, samples in by_target.items()}
    result['all'] = summarize([sample for samples in by_target.values() for sample in samples])
    return result


class TopSampler(threading.Thread):
    """Samples `kubectl top pods` until stopped; keeps first, peak and last values per Pod."""

    def __init__(self, command, interval):
        super().__init__(daemon=True)
        self.command, self.interval = command, interval
        self.stop, self.samples, self.errors = threading.Event(), [], []

    def sample(self):
        try:
            self.samples.append(parse_top(subprocess.check_output(
                self.command, text=True, stderr=subprocess.PIPE, timeout=30)))
        except (OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
            self.errors.append(str(getattr(error, 'stderr', None) or error).strip()[:300])

    def run(self):
        while not self.stop.wait(self.interval):
            self.sample()

    def report(self):
        pods = sorted({pod for sample in self.samples for pod in sample})
        series = lambda pod, key: [s[pod][key] for s in self.samples if pod in s]
        return {'samples': len(self.samples), 'errors': self.errors[:5], 'pods': {
            pod: {key: {'first': series(pod, key)[0], 'peak': max(series(pod, key)), 'last': series(pod, key)[-1]}
                  for key in ('cpuMillicores', 'memoryMi')} for pod in pods}}


def target(text):
    label, port, path = text.split(':', 2)
    return label, int(port), path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--requests', type=int, default=600, choices=range(1, 10001), metavar='1..10000')
    parser.add_argument('--concurrency', type=int, default=4, choices=range(1, 17), metavar='1..16')
    parser.add_argument('--timeout', type=int, default=10, choices=range(1, 61), metavar='1..60',
                        help='Per-request socket timeout seconds')
    parser.add_argument('--target', type=target, action='append',
                        help='LABEL:PORT:PATH on 127.0.0.1 (repeatable; replaces the default read set)')
    parser.add_argument('--kubeconfig', default=str(ROOT / '.local/k8s/kubeconfig'))
    parser.add_argument('--namespace', default='msc')
    parser.add_argument('--top-interval', type=int, default=5, choices=range(1, 61), metavar='1..60',
                        help='Seconds between kubectl top samples')
    parser.add_argument('--output', type=Path, help='Also write the JSON evidence here')
    args = parser.parse_args()
    env = dict(line.split('=', 1) for line in (ROOT / '.local/msa.env').read_text().splitlines()
               if '=' in line and not line.startswith('#'))
    authorizations = {user: 'Basic ' + base64.b64encode(f'{user}:{env[key]}'.encode()).decode()
                      for user, key in PASSWORDS.items()}
    sampler = TopSampler(['kubectl', '--kubeconfig', args.kubeconfig, '-n', args.namespace,
                          'top', 'pods', '--no-headers'], args.top_interval)
    sampler.sample()
    sampler.start()
    started_at = datetime.now(timezone.utc)
    start = time.monotonic()
    latency = run(args.target or DEFAULT_TARGETS, args.requests, args.concurrency, args.timeout, authorizations)
    duration = time.monotonic() - start
    sampler.stop.set()
    sampler.join()
    sampler.sample()
    evidence = {
        'environment': 'local',
        'claim': 'Local smoke measurement on one machine; not production capacity or a throughput rating.',
        'startedAtUtc': started_at.isoformat(timespec='seconds'), 'durationSeconds': round(duration, 2),
        'machine': {'platform': platform.platform(), 'processor': platform.processor() or platform.machine(),
                    'cpus': os.cpu_count(),
                    'memoryGiB': round(os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') / 2**30, 1)},
        'parameters': {'requests': args.requests, 'concurrency': args.concurrency, 'timeoutSeconds': args.timeout,
                       'targets': [f'{l}:{p}:{u}' for l, p, u in args.target or DEFAULT_TARGETS]},
        'latency': latency, 'kubectlTop': sampler.report()}
    text = json.dumps(evidence, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + '\n')
    print(text)


if __name__ == '__main__':
    main()
