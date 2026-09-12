#!/usr/bin/env python3
"""Render Deployment/Service objects from built image evidence, without embedding secrets."""
import argparse
import json
from pathlib import Path


def render(images, namespace, orekit_volume):
    items = [{'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': namespace}}]
    for name, evidence in sorted(images.items()):
        labels = {'app.kubernetes.io/name': name, 'app.kubernetes.io/part-of': 'mini-sat-controller'}
        container = {
            'name': name, 'image': evidence['image'], 'imagePullPolicy': 'IfNotPresent',
            'ports': [{'name': 'http', 'containerPort': 8080}],
            'envFrom': [{'configMapRef': {'name': 'msc-runtime'}},
                        {'secretRef': {'name': f'msc-{name}-runtime'}}],
            'env': [{'name': 'JAVA_TOOL_OPTIONS', 'value': '-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError'}],
            'resources': {'requests': {'cpu': '250m', 'memory': '256Mi'},
                          'limits': {'cpu': '2', 'memory': '768Mi' if name == 'flight-dynamics' else '512Mi'}},
            'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                                'capabilities': {'drop': ['ALL']}},
            'startupProbe': {'httpGet': {'path': '/actuator/health/liveness', 'port': 'http'},
                             'periodSeconds': 5, 'failureThreshold': 60},
            'livenessProbe': {'httpGet': {'path': '/actuator/health/liveness', 'port': 'http'},
                              'periodSeconds': 10, 'timeoutSeconds': 3, 'failureThreshold': 3},
            'readinessProbe': {'httpGet': {'path': '/actuator/health/readiness', 'port': 'http'},
                               'periodSeconds': 5, 'timeoutSeconds': 3, 'failureThreshold': 3},
            'volumeMounts': [{'name': 'temporary', 'mountPath': '/tmp'}]}
        volumes = [{'name': 'temporary', 'emptyDir': {'sizeLimit': '128Mi'}}]
        if name == 'flight-dynamics':
            container['volumeMounts'].append({'name': 'orekit', 'mountPath': '/orekit', 'readOnly': True})
            volumes.append({'name': 'orekit', **orekit_volume})
        items.append({'apiVersion': 'apps/v1', 'kind': 'Deployment',
                      'metadata': {'name': name, 'namespace': namespace, 'labels': labels},
                      'spec': {'replicas': 1, 'revisionHistoryLimit': 3, 'progressDeadlineSeconds': 600,
                               'selector': {'matchLabels': labels},
                               'strategy': {'type': 'RollingUpdate', 'rollingUpdate': {'maxSurge': 1, 'maxUnavailable': 0}},
                               'template': {'metadata': {'labels': labels, 'annotations': {'msc.jar.sha256': evidence['jarSha256']}},
                                            'spec': {'automountServiceAccountToken': False,
                                                     'terminationGracePeriodSeconds': 30,
                                                     'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001,
                                                                         'runAsGroup': 10001, 'fsGroup': 10001,
                                                                         'seccompProfile': {'type': 'RuntimeDefault'}},
                                                     'containers': [container], 'volumes': volumes}}}})
        items.append({'apiVersion': 'v1', 'kind': 'Service',
                      'metadata': {'name': name, 'namespace': namespace, 'labels': labels},
                      'spec': {'selector': labels, 'ports': [{'name': 'http', 'port': 8080, 'targetPort': 'http'}]}})
    return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, required=True)
    parser.add_argument('--namespace', default='msc')
    parser.add_argument('--local-orekit-host-path', help='Only for the project-owned local kind node')
    args = parser.parse_args()
    volume = ({'hostPath': {'path': args.local_orekit_host_path, 'type': 'Directory'}}
              if args.local_orekit_host_path else {'persistentVolumeClaim': {'claimName': 'msc-orekit'}})
    print(json.dumps(render(json.loads(args.images.read_text()), args.namespace, volume), indent=2))


if __name__ == '__main__':
    main()
