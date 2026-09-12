#!/usr/bin/env python3
"""Check deployed preparation rejection boundaries; no synthetic committed schedule is inserted."""
import importlib
import json
import uuid

api = importlib.import_module('verify-public-orbit')


def main():
    call = api.call
    run = 'missing-schedule-' + str(uuid.uuid4())
    tai = lambda s: {'seconds': s, 'nanos': 0, 'scale': 'TAI'}
    key = {'spacecraftId': {'value': run}, 'horizon': {'start': tai(1000), 'end': tai(2000)}}
    query = {'key': key, 'version': 1}
    call(8102, 'POST', '/api/planning/schedules/query', query, user='requester', expected=403)
    call(8102, 'POST', '/internal/planning/schedules/query', query, expected=404)
    request = {'id': {'value': run}, 'schedule': key, 'scheduleVersion': 1, 'correlationVersion': 1,
               'catalogsByActivity': {'absent': {'catalogId': 'absent', 'catalogVersion': 1}},
               'parametersByActivity': {'absent': {}}, 'deadline': tai(1100)}
    call(8107, 'POST', '/api/command-loads/prepare', request, run, 'requester', expected=403)
    call(8107, 'POST', '/api/command-loads/prepare', request, run, 'operator1', expected=404)
    call(8107, 'GET', '/internal/command-loads/' + run, expected=404)
    fixture = json.loads((api.ROOT / '.local/control-evidence-verification.json').read_text())
    existing = call(8107, 'GET', '/internal/simulation-execution-evidence/' + fixture['scenarioId'] + '/' + fixture['loadId'])
    assert existing['body']['bindingStatus'] == 'UNBOUND_SIMULATION_EVIDENCE'
    report = {'checks': ['schedule query requester denied', 'missing owner schedule rejected',
                         'preparation requester denied', 'owner absence preserved as 404',
                         'no prepared artifact on failure', 'prior Control evidence preserved'],
              'scope': 'Deployed rejection and persistence regression only; successful multi-service preparation awaits committed schedule production'}
    (api.ROOT / '.local/command-preparation-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
