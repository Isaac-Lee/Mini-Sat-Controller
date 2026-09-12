#!/usr/bin/env python3
"""Validate independent deployments and query the same durable attempt through both Planning Pods."""
import argparse
import importlib
import json
from pathlib import Path
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
call = importlib.import_module('verify-public-orbit').call


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', default=str(ROOT / '.local/k8s/kubeconfig'))
    args = parser.parse_args()
    command = ['kubectl', '--kubeconfig', args.kubeconfig, '-n', 'msc']
    get = lambda resource: json.loads(subprocess.check_output(command+['get', resource, '-o', 'json']))['items']
    images = json.loads((ROOT / '.local/service-images.json').read_text())
    deployments = {d['metadata']['name']: d for d in get('deployments')}
    assert set(deployments) == set(images)
    for name, deployment in deployments.items():
        expected = 2 if name == 'planning' else 1
        assert deployment['spec']['replicas'] == expected
        assert deployment['status']['observedGeneration'] == deployment['metadata']['generation']
        assert deployment['status'].get('updatedReplicas') == expected
        assert deployment['status'].get('readyReplicas') == expected
        assert deployment['spec']['template']['spec']['containers'][0]['image'] == images[name]['image']
    pods = [p for p in get('pods') if p['metadata']['labels']['app.kubernetes.io/name'] == 'planning'
            and not p['metadata'].get('deletionTimestamp')]
    assert len(pods) == 2
    workflow = json.loads((ROOT / '.local/planning-search-verification.json').read_text())
    attempt_id = workflow['attemptId']
    run_id = workflow.get('runId')
    results = []
    runs = []
    resources = []
    for pod in pods:
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        process = subprocess.Popen(command+['port-forward', '--address', '127.0.0.1',
                                           'pod/'+pod['metadata']['name'], f'{port}:8080'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic()+30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Port forward exited before accepting connections')
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=.5):
                        break
                except OSError:
                    time.sleep(.2)
            else:
                raise TimeoutError('Port forward did not become ready')
            result = call(port, 'GET', '/internal/planning/input-attempts/'+attempt_id)
            assert result['id'] == attempt_id and result['assets']
            results.append(result)
            if run_id:
                run = call(port, 'GET', '/internal/planning/runs/'+run_id)
                assert run['id'] == run_id and run['inputAttemptId'] == attempt_id
                assert run['run']['candidates']
                runs.append(run)
                if workflow.get('resourceAssessmentRunId'):
                    assessment = call(port, 'GET', '/internal/planning/runs/'+run_id+'/resources')
                    assert assessment['runId'] == run_id and assessment['inputAttemptId'] == attempt_id
                    assert assessment['scheduleContextStatus'] == 'CURRENT_HEADS_CAPTURED'
                    assert assessment['candidates'] and all(c['forecast'] for c in assessment['candidates'])
                    resources.append(assessment)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    assert results[0] == results[1]
    if run_id:
        assert runs[0] == runs[1]
    if workflow.get('resourceAssessmentRunId'):
        if len(resources) != 2 or resources[0] != resources[1]:
            (ROOT / '.local/k8s/resource-replica-mismatch.json').write_text(json.dumps(resources, indent=2)+'\n')
            raise AssertionError('Resource API representations differ between Planning replicas')
    evidence = {'deployments': len(deployments), 'planningReplicas': 2,
                'otherReplicaCounts': {n: d['spec']['replicas'] for n, d in deployments.items() if n != 'planning'},
                'attemptId': attempt_id,
                'planningPods': [{'name': p['metadata']['name'], 'uid': p['metadata']['uid'],
                                  'imageID': p['status']['containerStatuses'][0]['imageID']} for p in pods],
                'passed': ['independent images/deployments', 'only Planning scaled to two',
                           'all desired replicas ready', 'both Planning Pods serve identical durable evidence'],
                'scope': 'Local kind with shared local infrastructure; no production load/HPA claim'}
    if run_id:
        evidence['runId'] = run_id
        evidence['passed'].append('both Planning Pods serve the identical linked durable run')
    if workflow.get('resourceAssessmentRunId'):
        evidence['resourceAssessmentRunId'] = run_id
        evidence['passed'].append('both Planning Pods serve the identical source-bound resource assessment')
    (ROOT / '.local/k8s/verification.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
