#!/usr/bin/env python3
"""Exercise Simulator -> RabbitMQ -> independent Control evidence storage."""
import importlib
import json
import time
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    importlib.import_module('verify-simulation-reception').main()
    fixture = json.loads((api.ROOT / '.local/simulation-reception-verification.json').read_text())
    scenario, load = fixture['scenarioId'], fixture['loadId']
    source = api.call(8114, 'POST', f'/internal/simulation/scenarios/{scenario}/loads/{load}/receive',
                      {'channel': 'RECONCILIATION'}, 'control-proof-' + str(uuid.uuid4()))['observation']
    path = f'/api/simulation-execution-evidence/{scenario}/{load}'
    deadline = time.monotonic() + 30
    while True:
        received = api.call(8107, 'GET', path, expected=[200, 404])
        if 'body' in received:
            break
        assert time.monotonic() < deadline, 'Control has not received the delivered observation'
        time.sleep(.5)
    assert received['body']['observation'] == source
    assert received['body']['bindingStatus'] == 'UNBOUND_SIMULATION_EVIDENCE'
    assert received['body']['receivedAt']['scale'] == 'TAI'
    assert received['body']['eventCreatedAt']['scale'] == 'TAI'
    uuid.UUID(received['body']['sourceEventId'])
    for actor in ['admin', 'operator1']:
        assert api.call(8107, 'GET', path, user=actor) == received
    api.call(8107, 'GET', path, user='requester', expected=403)
    assert api.call(8107, 'GET', path.replace('/api/', '/internal/')) == received
    report = {'scenarioId': scenario, 'loadId': load, 'sourceEventId': received['body']['sourceEventId'],
              'checks': ['actual broker delivery to independent Control service',
                         'exact simulation observation preserved', 'unbound status retained',
                         'admin operator service reads', 'requester denied'],
              'scope': 'Evidence ingestion only; release gate and operational confirmation remain incomplete'}
    (api.ROOT / '.local/control-evidence-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
