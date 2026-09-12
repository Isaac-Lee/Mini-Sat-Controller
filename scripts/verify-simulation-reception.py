#!/usr/bin/env python3
"""Verify delivery faults and reconciliation on a fresh modeled command fixture."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    importlib.import_module('verify-simulation-command').main()
    fixture = json.loads((api.ROOT / '.local/simulation-command-verification.json').read_text())
    scenario_id, load_id = fixture['scenarioId'], fixture['loadId']
    call = api.call
    scenario_path = '/internal/simulation/scenarios/' + scenario_id
    scenario = call(8114, 'GET', scenario_path)
    path = f'/simulation/scenarios/{scenario_id}/loads/{load_id}'
    key = 'reception-' + str(uuid.uuid4())
    policy = {'expectedVersion': 0, 'connected': False, 'acknowledgmentLost': True,
              'notBeforeTick': scenario['body']['currentTick'] + 10,
              'provenance': 'synthetic delivery fault test'}
    call(8114, 'POST', '/api' + path + '/link', policy, key, 'admin')
    ack, reconcile = {'channel': 'ACKNOWLEDGMENT'}, {'channel': 'RECONCILIATION'}
    def attempt(body, suffix):
        return call(8114, 'POST', '/internal' + path + '/receive', body, key + suffix)
    disconnected = attempt(reconcile, '-disconnected')
    assert disconnected['belief'] == 'UNKNOWN' and disconnected['reason'] == 'DISCONNECTED'
    policy.update(expectedVersion=1, connected=True)
    call(8114, 'POST', '/api' + path + '/link', policy, key + '-connect', 'admin')
    delayed = attempt(reconcile, '-delayed')
    assert delayed['belief'] == 'UNKNOWN' and delayed['reason'] == 'DELAYED'
    advanced = call(8114, 'POST', f'/api/simulation/scenarios/{scenario_id}/advance',
                    {'expectedVersion': scenario['version'], 'targetTick': policy['notBeforeTick']}, key, 'admin')
    assert advanced['body']['reservoirs'] == scenario['body']['reservoirs']
    lost = attempt(ack, '-lost')
    assert lost['belief'] == 'UNKNOWN' and lost['reason'] == 'ACKNOWLEDGMENT_LOST'
    received = attempt(reconcile, '-reconciled')
    assert received['belief'] == 'OBSERVED'
    observation = received['observation']
    assert observation['scenarioId'] == scenario_id and observation['loadId'] == load_id
    assert observation['environment'] == 'SIMULATION'
    assert observation['commands'][0]['outcome'] == 'EFFECT_APPLIED'
    assert attempt(reconcile, '-again')['observation'] == observation
    assert attempt(ack, '-lost') == lost
    assert call(8114, 'GET', scenario_path) == advanced
    report = {'scenarioId': scenario_id, 'loadId': load_id,
              'checks': ['disconnected remains unknown', 'delay remains unknown',
                         'lost acknowledgment remains unknown', 'reconciliation delivers modeled evidence',
                         'duplicate reception preserves observation', 'old attempt replay remains unchanged',
                         'reception does not reapply command effects'],
              'scope': 'Simulator delivery evidence; Control and physical radio link not yet integrated'}
    (api.ROOT / '.local/simulation-reception-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
