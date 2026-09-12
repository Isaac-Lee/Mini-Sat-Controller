#!/usr/bin/env python3
"""Verify synthetic monitoring evidence, latched safety and distinct operator recovery over APIs."""
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
    run = uuid.uuid4().hex
    craft = 'sim-safety-' + run
    source = 'simulator:' + run
    binding = {'spacecraftId': craft, 'version': 1, 'source': source, 'environment': 'SIMULATION',
               'maximumAgeSeconds': 120, 'futureSkewSeconds': 0, 'approvalReference': 'synthetic test admission'}
    call(8109, 'POST', '/api/telemetry-bindings', binding, run, user='admin')
    policy = {'spacecraftId': craft, 'version': 1, 'telemetryBindingVersion': 1,
              'minimumBatteryWh': 20, 'maximumStorageMb': 500, 'minimumPropellantKg': 1,
              'recoveryApprovals': 2, 'approvalValiditySeconds': 60, 'approvalReference': 'synthetic policy; not hardware qualified'}
    base = '/api/safety/' + craft
    call(8110, 'POST', '/api/safety-policies', policy, run, user='requester', expected=403)
    configured = call(8110, 'POST', '/api/safety-policies', policy, run, user='admin')
    assert configured['body']['frozen']
    call(8110, 'GET', base, user='requester', expected=403)

    def state():
        return call(8110, 'GET', base)

    def check():
        return call(8110, 'POST', '/internal/safety/' + craft + '/check')

    def emit(seq, mode='NOMINAL', binding_version=1):
        now = call(8103, 'POST', '/internal/time/utc-to-tai', {'utc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')})
        frame = {'id': str(uuid.uuid4()), 'spacecraftId': craft, 'bindingVersion': binding_version,
                 'source': source, 'sequence': seq, 'observedAt': now, 'quality': 'GOOD', 'mode': mode,
                 'batteryWh': 100, 'storageMb': 20, 'propellantKg': 2, 'provenance': 'explicit safety verification scenario'}
        call(8114, 'POST', '/api/simulation/telemetry', frame, frame['id'], user='admin')
        for _ in range(50):
            receipt = call(8109, 'GET', f'/internal/telemetry/{craft}/{frame["id"]}', expected=[200, 404])
            if receipt.get('disposition'):
                assert receipt['disposition'] == 'ACCEPTED', receipt
                return frame
            time.sleep(.2)
        raise AssertionError('Telemetry not delivered')

    def approve(user):
        body = {'expectedSafetyVersion': state()['version'], 'decisionReference': 'reviewed synthetic scenario ' + uuid.uuid4().hex}
        return call(8110, 'POST', base + '/recovery-approvals', body, uuid.uuid4().hex, user=user)

    emit(1)
    assert not check()['clear']
    first = approve('operator1')
    assert first['approved'] and first['state']['body']['frozen']
    assert approve('operator1')['state']['body']['frozen']
    second = approve('operator2')
    assert second['approved'] and not second['state']['body']['frozen']
    assert check()['clear']
    critical = emit(2, 'SAFE')
    for _ in range(50):
        if state()['body']['frozen']:
            break
        time.sleep(.2)
    else:
        raise AssertionError('Critical observation did not latch a freeze')
    denied = approve('operator1')
    assert not denied['approved'] and 'SAFE_MODE' in denied['check']['currentReasons']
    emit(3)
    normal_but_frozen = check()
    assert not normal_but_frozen['clear'] and normal_but_frozen['currentReasons'] == []
    assert approve('operator1')['state']['body']['frozen']
    generation = state()['body']['generation']
    emit(4, 'SAFE')
    for _ in range(50):
        if state()['body']['generation'] > generation:
            break
        time.sleep(.2)
    assert state()['body']['approvals'] == []
    emit(5)
    assert approve('operator2')['state']['body']['frozen']
    assert not approve('operator1')['state']['body']['frozen']
    assert check()['clear']
    # Rotate to a short simulation freshness window and prove a timer-only freeze.
    call(8109, 'POST', '/api/telemetry-bindings', {**binding, 'version': 2, 'maximumAgeSeconds': 5}, uuid.uuid4().hex, user='admin')
    call(8110, 'POST', '/api/safety-policies', {**policy, 'version': 2, 'telemetryBindingVersion': 2}, uuid.uuid4().hex, user='admin')
    emit(1, binding_version=2)
    assert approve('operator1')['state']['body']['frozen']
    assert not approve('operator2')['state']['body']['frozen']
    assert check()['clear']
    for _ in range(100):
        final = state()  # Read-only; does not invoke the synchronous check.
        if final['body']['frozen'] and 'STALE_STATE' in final['body']['reasons']:
            break
        time.sleep(.2)
    else:
        raise AssertionError('Telemetry silence not detected by safety watchdog')
    history = call(8110, 'GET', base + '/history')
    assert history[0] == configured and history[-1] == final
    incidents = [entry for entry in call(8110, 'GET', '/api/anomalies?limit=500')
                 if entry['body']['anomaly']['spacecraftId']['value'] == craft]
    assert len(incidents) >= 3
    resolved = [call(8110, 'GET', '/api/anomalies/' + entry['id']) for entry in incidents]
    assert any(item['resolution'] is not None for item in resolved)
    assert any(item['resolution'] is None for item in resolved)
    evidence = {'spacecraftId': craft, 'finalSafetyVersion': final['version'], 'finalState': final['body'],
                'historyVersions': len(history), 'criticalFrameId': critical['id'],
                'passed': ['policy role restriction', 'initial operator enable', 'two distinct operators',
                           'SAFE telemetry freeze', 'unsafe recovery denied', 'no automatic recovery on nominal telemetry',
                           'new critical evidence revokes pending approvals', 'explicit policy/source revision',
                           'timer detects complete telemetry silence', 'immutable safety history',
                           'Anomaly records and scoped resolution history'],
                'scope': 'Synthetic policies and telemetry; no commanding or atomic cross-service release permit'}
    (ROOT / '.local/safety-verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
