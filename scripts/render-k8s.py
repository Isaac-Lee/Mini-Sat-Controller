#!/usr/bin/env python3
"""Render Deployment/Service objects from built image evidence, without embedding secrets."""
import argparse
import json
from pathlib import Path

# Services with recorded multi-replica checks (verify-k8s.py, verify-tasking-replicas.py).
# Others hold workers/safety gates whose concurrent-replica behavior is not verified.
HPA_SERVICES = {'acquisition', 'planning', 'tasking'}
HPA_MAX_REPLICAS = 2
MAX_SURGE = 1


def database_connections(images, pool_size):
    """Worst case: every HPA service at max replicas plus one service mid-rollout with a surge Pod."""
    pods = sum(HPA_MAX_REPLICAS if name in HPA_SERVICES else 1 for name in images) + MAX_SURGE
    return pods * pool_size


def autoscaler(name, namespace, labels):
    return {'apiVersion': 'autoscaling/v2', 'kind': 'HorizontalPodAutoscaler',
            'metadata': {'name': name, 'namespace': namespace, 'labels': labels},
            'spec': {'scaleTargetRef': {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'name': name},
                     'minReplicas': 1, 'maxReplicas': HPA_MAX_REPLICAS,
                     # 80% of the 250m request; the 2-CPU limit leaves headroom while a second Pod starts.
                     'metrics': [{'type': 'Resource', 'resource': {
                         'name': 'cpu', 'target': {'type': 'Utilization', 'averageUtilization': 80}}}],
                     # JVM startup bursts far above the request; wait before reacting to them.
                     'behavior': {'scaleUp': {'stabilizationWindowSeconds': 60},
                                  'scaleDown': {'stabilizationWindowSeconds': 300}}}}


def render(images, namespace, orekit_volume, hpa_pool_size=None):
    """hpa_pool_size enables HPAs and pins MSC_DATABASE_POOL_SIZE so the connection budget holds."""
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
        if hpa_pool_size is not None:
            container['env'].append({'name': 'MSC_DATABASE_POOL_SIZE', 'value': str(hpa_pool_size)})
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
        if hpa_pool_size is not None and name in HPA_SERVICES:
            del items[-1]['spec']['replicas']  # the HPA owns the replica count
            items.append(autoscaler(name, namespace, labels))
        items.append({'apiVersion': 'v1', 'kind': 'Service',
                      'metadata': {'name': name, 'namespace': namespace, 'labels': labels},
                      'spec': {'selector': labels, 'ports': [{'name': 'http', 'port': 8080, 'targetPort': 'http'}]}})
    return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def local_metrics_server():
    """metrics-server v0.7.2 components.yaml for the project-owned kind node only.

    --kubelet-insecure-tls skips kubelet certificate verification; never apply this to a shared
    or production cluster, and skip it where the cluster already runs metrics-server (k3s).
    """
    ns, app = 'kube-system', {'k8s-app': 'metrics-server'}
    meta = lambda name, **extra: {'name': name, 'labels': {**app, 'msc.local-only': 'true'}, **extra}
    subject = [{'kind': 'ServiceAccount', 'name': 'metrics-server', 'namespace': ns}]
    role_ref = lambda kind, name: {'apiGroup': 'rbac.authorization.k8s.io', 'kind': kind, 'name': name}
    reader = meta('system:aggregated-metrics-reader')
    reader['labels'].update({f'rbac.authorization.k8s.io/aggregate-to-{r}': 'true' for r in ('admin', 'edit', 'view')})
    probe = lambda path: {'httpGet': {'path': path, 'port': 'https', 'scheme': 'HTTPS'},
                          'periodSeconds': 10, 'failureThreshold': 3}
    return [
        {'apiVersion': 'v1', 'kind': 'ServiceAccount', 'metadata': meta('metrics-server', namespace=ns)},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRole', 'metadata': reader,
         'rules': [{'apiGroups': ['metrics.k8s.io'], 'resources': ['pods', 'nodes'],
                    'verbs': ['get', 'list', 'watch']}]},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRole', 'metadata': meta('system:metrics-server'),
         'rules': [{'apiGroups': [''], 'resources': ['nodes/metrics'], 'verbs': ['get']},
                   {'apiGroups': [''], 'resources': ['pods', 'nodes'], 'verbs': ['get', 'list', 'watch']}]},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'RoleBinding',
         'metadata': meta('metrics-server-auth-reader', namespace=ns),
         'roleRef': role_ref('Role', 'extension-apiserver-authentication-reader'), 'subjects': subject},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRoleBinding',
         'metadata': meta('metrics-server:system:auth-delegator'),
         'roleRef': role_ref('ClusterRole', 'system:auth-delegator'), 'subjects': subject},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRoleBinding',
         'metadata': meta('system:metrics-server'),
         'roleRef': role_ref('ClusterRole', 'system:metrics-server'), 'subjects': subject},
        {'apiVersion': 'v1', 'kind': 'Service', 'metadata': meta('metrics-server', namespace=ns),
         'spec': {'selector': app, 'ports': [{'name': 'https', 'port': 443, 'protocol': 'TCP', 'targetPort': 'https'}]}},
        {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': meta('metrics-server', namespace=ns),
         'spec': {'selector': {'matchLabels': app}, 'strategy': {'rollingUpdate': {'maxUnavailable': 0}},
                  'template': {'metadata': {'labels': app}, 'spec': {
                      'serviceAccountName': 'metrics-server', 'priorityClassName': 'system-cluster-critical',
                      'nodeSelector': {'kubernetes.io/os': 'linux'},
                      'containers': [{
                          'name': 'metrics-server', 'image': 'registry.k8s.io/metrics-server/metrics-server:v0.7.2',
                          'imagePullPolicy': 'IfNotPresent',
                          'args': ['--cert-dir=/tmp', '--secure-port=10250',
                                   '--kubelet-preferred-address-types=InternalIP,ExternalIP,Hostname',
                                   '--kubelet-use-node-status-port', '--metric-resolution=15s',
                                   '--kubelet-insecure-tls'],
                          'ports': [{'name': 'https', 'containerPort': 10250, 'protocol': 'TCP'}],
                          'livenessProbe': probe('/livez'),
                          'readinessProbe': {**probe('/readyz'), 'initialDelaySeconds': 20},
                          'resources': {'requests': {'cpu': '100m', 'memory': '200Mi'}},
                          'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                                              'runAsNonRoot': True, 'runAsUser': 1000,
                                              'seccompProfile': {'type': 'RuntimeDefault'},
                                              'capabilities': {'drop': ['ALL']}},
                          'volumeMounts': [{'name': 'tmp-dir', 'mountPath': '/tmp'}]}],
                      'volumes': [{'name': 'tmp-dir', 'emptyDir': {}}]}}}},
        {'apiVersion': 'apiregistration.k8s.io/v1', 'kind': 'APIService', 'metadata': meta('v1beta1.metrics.k8s.io'),
         'spec': {'group': 'metrics.k8s.io', 'groupPriorityMinimum': 100, 'insecureSkipTLSVerify': True,
                  'service': {'name': 'metrics-server', 'namespace': ns}, 'version': 'v1beta1',
                  'versionPriority': 100}}]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, required=True)
    parser.add_argument('--namespace', default='msc')
    parser.add_argument('--local-orekit-host-path', help='Only for the project-owned local kind node')
    parser.add_argument('--hpa', action='store_true',
                        help=f'HPA (min 1, max {HPA_MAX_REPLICAS}) for {", ".join(sorted(HPA_SERVICES))}')
    parser.add_argument('--database-pool-size', type=int, default=8,
                        help='Hikari pool per Pod, pinned on every Deployment with --hpa (platform default 8)')
    parser.add_argument('--database-max-connections', type=int, default=100,
                        help='PostgreSQL max_connections shared by all service databases (server default 100)')
    parser.add_argument('--local-metrics-server', action='store_true',
                        help='Append metrics-server for the project-owned local kind node only')
    args = parser.parse_args()
    volume = ({'hostPath': {'path': args.local_orekit_host_path, 'type': 'Directory'}}
              if args.local_orekit_host_path else {'persistentVolumeClaim': {'claimName': 'msc-orekit'}})
    images = json.loads(args.images.read_text())
    if args.hpa:
        needed = database_connections(images, args.database_pool_size)
        if needed > args.database_max_connections:
            parser.error(f'worst-case {needed} database connections exceed --database-max-connections '
                         f'{args.database_max_connections}; lower --database-pool-size')
    manifest = render(images, args.namespace, volume, args.database_pool_size if args.hpa else None)
    if args.local_metrics_server:
        manifest['items'] += local_metrics_server()
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
