#!/usr/bin/env python3
"""Keep local API ports connected to the project K8s services, without duplicate host JVMs."""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PORTS = {'tasking': 8101, 'planning': 8102, 'flight-dynamics': 8103,
         'mission-definition': 8104, 'reference-data': 8105, 'ground-operations': 8106,
         'spacecraft-control': 8107,
         'monitoring': 8109, 'anomaly': 8110, 'acquisition': 8111, 'simulator': 8114}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', default=str(ROOT / '.local/k8s/kubeconfig'))
    args = parser.parse_args()
    log_dir = ROOT / '.local/k8s/forwards'
    log_dir.mkdir(parents=True, exist_ok=True)
    command = ['kubectl', '--kubeconfig', args.kubeconfig, '-n', 'msc',
               'port-forward', '--address', '127.0.0.1']
    # Refuse to replace any existing listener; host JVM shutdown is a separate explicit step.
    for name, port in PORTS.items():
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError as exc:
                raise RuntimeError(f'{name} port {port} is already in use') from exc
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    children = {}
    logs = {}
    retry_at = {name: 0 for name in PORTS}
    failures = {name: 0 for name in PORTS}
    health = {name: 'STARTING' for name in PORTS}
    probe_at = time.monotonic() + 5
    state_file = ROOT / '.local/k8s/local-forward-state.json'
    try:
        for name in PORTS:
            fd = os.open(log_dir / (name + '.log'), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            logs[name] = os.fdopen(fd, 'ab', buffering=0)
        while not stopping:
            changed = False
            for name, port in PORTS.items():
                child = children.get(name)
                if child is not None and child.poll() is not None:
                    print(f'{name}: forward exited {child.returncode}; reconnecting to service', flush=True)
                    del children[name]
                    retry_at[name] = time.monotonic() + 5
                    child = None
                    changed = True
                if child is None and time.monotonic() >= retry_at[name]:
                    children[name] = subprocess.Popen(command + ['service/' + name, f'{port}:8080'],
                                                       stdout=logs[name], stderr=logs[name])
                    failures[name] = 0
                    health[name] = 'STARTING'
                    print(f'{name}: 127.0.0.1:{port} -> K8s service/{name}', flush=True)
                    changed = True
            # kubectl can keep its listener alive after the selected Pod disappears. Probe the
            # tunnel as well as the child: do not leave the first user request to discover it.
            if time.monotonic() >= probe_at:
                for name, child in children.items():
                    try:
                        with urllib.request.urlopen(
                                f'http://127.0.0.1:{PORTS[name]}/actuator/health/liveness',
                                timeout=.8) as response:
                            healthy = json.load(response).get('status') == 'UP'
                    except (OSError, ValueError):
                        healthy = False
                    next_health = 'UP' if healthy else 'UNAVAILABLE'
                    changed |= health[name] != next_health
                    health[name] = next_health
                    failures[name] = 0 if healthy else failures[name] + 1
                    if failures[name] >= 2 and child.poll() is None:
                        print(f'{name}: tunnel unresponsive; replacing own forward child', flush=True)
                        child.kill()
                probe_at = time.monotonic() + 5
            if changed:
                state_file.write_text(json.dumps({'supervisorPid': os.getpid(),
                    'mode': 'K8S_ONLY_LOCAL_API_FORWARDS',
                    'forwards': {name: {'port': PORTS[name], 'pid': child.pid, 'health': health[name]}
                                 for name, child in children.items()}}, indent=2) + '\n')
            time.sleep(.5)
    finally:
        for child in children.values():
            if child.poll() is None:
                child.terminate()
        for child in children.values():
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        for log in logs.values():
            log.close()
        state_file.write_text(json.dumps({'supervisorPid': os.getpid(), 'status': 'STOPPED'}) + '\n')


if __name__ == '__main__':
    main()
