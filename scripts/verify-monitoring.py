#!/usr/bin/env python3
"""Explicit synthetic telemetry through Simulator/RabbitMQ/Monitoring. No spacecraft truth."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_api', ROOT / 'scripts/verify-public-orbit.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
call = api.call


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replica-port', type=int)
    args = parser.parse_args()
    run = uuid.uuid4().hex
    craft = 'sim-monitor-' + run
    source = 'simulator:' + run
    binding = {'spacecraftId': craft, 'version': 1, 'source': source, 'environment': 'SIMULATION',
               'maximumAgeSeconds': 60, 'futureSkewSeconds': 5, 'approvalReference': 'synthetic verification only'}
    call(8109, 'POST', '/api/telemetry-bindings', binding, run, user='requester', expected=403)
    configured = call(8109, 'POST', '/api/telemetry-bindings', binding, run, user='admin')
    assert configured['body']['accepted'] is None
    now = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')})

    def frame(seq, offset=0, **changes):
        return {'id': str(uuid.uuid4()), 'spacecraftId': craft, 'bindingVersion': 1, 'source': source,
                'sequence': seq, 'observedAt': {**now, 'seconds': now['seconds'] + offset}, 'quality': 'GOOD',
                'mode': 'NOMINAL', 'batteryWh': 100, 'storageMb': 20, 'propellantKg': 1,
                'provenance': 'explicit synthetic verification fixture', **changes}

    def emit(f, expected):
        saved = call(8114, 'POST', '/api/simulation/telemetry', f, f['id'], user='admin')
        for _ in range(60):
            receipt = call(8109, 'GET', f'/internal/telemetry/{craft}/{f["id"]}', expected=[200, 404])
            if receipt.get('disposition'):
                assert receipt['disposition'] == expected, receipt
                assert receipt['frame'] == f
                return saved
            time.sleep(.2)
        raise AssertionError('Simulator event did not reach Monitoring')

    def current(port=8109):
        return call(port, 'GET', '/internal/spacecraft-estimates/' + craft)

    first = frame(1, -10)
    call(8114, 'POST', '/api/simulation/telemetry', first, run, user='requester', expected=403)
    saved = emit(first, 'ACCEPTED')
    accepted = current()
    assert accepted['confidence'] == 'FRESH'
    assert accepted['estimate']['body']['binding']['environment'] == 'SIMULATION'
    pinned = accepted['estimate']['version']
    assert call(8114, 'POST', '/api/simulation/telemetry', first, run + '-duplicate', user='admin') == saved
    emit(frame(0, -20), 'OUT_OF_ORDER')
    assert current()['estimate'] == accepted['estimate']
    emit(frame(2, -5, quality='INVALID'), 'BAD_QUALITY')
    assert current()['confidence'] == 'DEGRADED'
    intermediate = frame(3, -7)
    emit(intermediate, 'ACCEPTED')
    assert current()['confidence'] == 'DEGRADED'
    assert current()['estimate']['body']['accepted']['frame'] == intermediate
    recovery = frame(4)
    emit(recovery, 'ACCEPTED')
    assert current()['confidence'] == 'FRESH'
    before_future = current()['estimate']
    emit(frame(5, 3600), 'FUTURE_TIMESTAMP')
    assert current()['estimate'] == before_future
    call(8109, 'POST', '/internal/telemetry', {**first, 'batteryWh': 10}, str(uuid.uuid4()), expected=409)
    history = call(8109, 'GET', f'/internal/spacecraft-estimates/{craft}/versions/{pinned}')
    assert history == accepted['estimate']
    assert call(8109, 'GET', f'/internal/spacecraft-estimates/{craft}/versions/1')['body']['accepted'] is None
    if args.replica_port:
        duplicate = frame(6)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(call, port, 'POST', '/internal/telemetry', duplicate, run + '-replica')
                       for port in [8109, args.replica_port]]
            responses = [future.result() for future in futures]
        assert responses[0] == responses[1]
        assert current(8109)['estimate'] == current(args.replica_port)['estimate']
    second_binding = {**binding, 'version': 2, 'source': source + '-replacement', 'maximumAgeSeconds': 2}
    call(8109, 'POST', '/api/telemetry-bindings', second_binding, str(uuid.uuid4()), user='admin')
    assert current()['confidence'] == 'UNKNOWN'
    emit(frame(7), 'SUPERSEDED_BINDING')
    assert current()['confidence'] == 'UNKNOWN'
    replacement = frame(1, -10, bindingVersion=2, source=second_binding['source'])
    emit(replacement, 'ACCEPTED')
    assert current()['confidence'] == 'STALE'
    evidence = {'spacecraftId': craft, 'sourceEnvironment': 'SIMULATION', 'pinnedEstimateVersion': pinned,
                'finalEstimateVersion': current()['estimate']['version'], 'finalConfidence': 'STALE',
                'passed': ['role checks', 'Simulator outbox to RabbitMQ to Monitoring inbox',
                           'duplicates and contradictory identity rejection', 'out-of-order evidence',
                           'bad quality degradation and recovery', 'future timestamp exclusion',
                           'immutable estimate history', 'source rotation and delayed old binding',
                           'freshness from observation time'],
                'scope': 'Explicit telemetry scenario injection; not physical vehicle execution or real telemetry'}
    if args.replica_port:
        evidence['passed'].append('two-JVM idempotency and shared estimate')
    (ROOT / '.local/monitoring-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
