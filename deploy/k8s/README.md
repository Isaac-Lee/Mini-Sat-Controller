# Independent service deployment

`scripts/build-service-images.py` builds one image per executable service in the
root Maven reactor. Each Docker build receives only the executable JAR and the
service Dockerfile, never the checkout, `.local` data or credentials. Runtime
images must be supplied by digest. Image tags bind both the JAR hash and runtime
digest. The generated image manifest is an artifact, not a registry publication.

`scripts/render-k8s.py --images .local/service-images.json` renders one Deployment
and one ClusterIP Service per image. Each Deployment owns its own replica count,
image, rollout and resource settings. Inter-service HTTP resolves the existing
service names through cluster DNS. Startup/liveness probes check process health;
readiness additionally includes database and RabbitMQ health. Containers run as
UID 10001, with a read-only root filesystem and a bounded writable `/tmp`.

The namespace needs a non-secret `msc-runtime` ConfigMap and a distinct
`msc-<service>-runtime` Secret per service. Populate the existing runtime keys from
`msc-platform/src/main/resources/application-platform.yaml` and the particular
service's application configuration. Each Secret must contain only its own
database credentials. A production setup must use the configured OIDC issuer,
audience and client credentials, appropriate managed infrastructure endpoints,
and deployment-specific time/EOP references. The local Basic-auth setup below
is explicitly a simulation validation environment.

Flight Dynamics mounts a read-only `msc-orekit` PVC at `/orekit`. Supply the
approved archive path and SHA256 through configuration. Multi-node deployments
must make the same pinned archive available on every eligible node. The local
renderer override uses the dedicated kind node's read-only mount instead.

Resource requests/limits are initial local validation settings, not production
capacity measurements. Database pool size must be budgeted across all replicas,
including rollout surge. HPA and metrics-server installation are not claimed;
the explicit replica count allows independent scale testing first.

## Local kind workflow

Use a dedicated cluster named `msc-local` and always pass
`--kubeconfig .local/k8s/kubeconfig`; do not change the user's global context.
The kind configuration must mount the approved local Orekit archive directory
read-only into the node at `/msc-orekit`.

After building the reactor and images:

```sh
python3 scripts/prepare-local-k8s-runtime.py
python3 scripts/render-k8s.py --images .local/service-images.json \
  --local-orekit-host-path /msc-orekit > .local/k8s/services.json
kubectl --kubeconfig .local/k8s/kubeconfig apply -f .local/k8s/runtime.json
kubectl --kubeconfig .local/k8s/kubeconfig apply -f .local/k8s/services.json
kubectl --kubeconfig .local/k8s/kubeconfig -n msc scale deployment planning --replicas=2
```

Load the image manifest's tags with `kind load docker-image ... --name msc-local`
before applying. `runtime.json` is private (mode 0600) and must never be committed
or printed. It references the existing local PostgreSQL/RabbitMQ/MinIO over
`host.docker.internal`; these are shared with the host JVMs to test actual
multi-replica ownership, not separate fake databases. No ingress is configured.

Sources: [Kubernetes probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/)
and [kind setup/image loading](https://kind.sigs.k8s.io/docs/user/quick-start/).
## Local verification and resource contention

The dedicated kind cluster deployed nine services and served the same durable
Planning attempt through two different Planning Pods. Evidence is saved in
`.local/k8s/verification.json`; this proves the recorded read/replica checks,
not sustained availability or new-work completion. A subsequent new-work check
timed out and exposed geometry cache hits incorrectly consuming the per-attempt
HTTP budget; the correction was subsequently deployed and exercised as recorded below.

A later state check found startup/liveness timeouts and repeated Pod restarts
under shared Docker CPU contention. The initial Ready snapshot therefore does not
prove ongoing availability. During recovery, this project's Deployments were
scaled to zero while host services remained running; restore services one at a
time and verify them before adding the next. Preserve unrelated clusters and
infrastructure. Run container-based test suites separately from simultaneous
cold-start deployment validation on a constrained local machine. Production
resource sizing and sustained scale-out validation remain outstanding.


After sequential recovery, all ten Pods were Ready with zero restarts at the
verification checkpoint. Initial replica startup took 16–33 seconds per service.
The corrected Planning image is recorded in `.local/service-images.json`.
With host Planning stopped, `verify-planning-search.py --timeout-seconds 300`
passed all nine assertions in 20.005 seconds. Request
`ae62e881-bb0a-497a-90f0-c6cee45690c4` produced attempt
`38c9d643-2474-48da-b56d-637b262696b4` and was cancelled during verifier cleanup.
`verify-k8s.py` then passed four deployment/read checks, including retrieval of
that exact new attempt through both Planning Pods. Host Planning was restored and
its readiness endpoint returned UP.

The preceding 120-second failure is retained: its two attempts searched different
five-minute TAI buckets (1789148100 and 1789148400), requiring fresh calculations
across rollover before the rotated target was reached. The verifier's configurable
wait is bounded to 900 seconds and reports actual elapsed time. It changes no
geometry, provenance, version or feasibility assertion and establishes no latency
SLA. Full candidate validation, schedule commitment and downstream operations
remain outside this point-search evidence.

## K8s-only local API ports

The active local verification runtime uses Pods for all nine services; host service
JVMs were stopped after repeat startup/liveness failures under concurrent duplicate
execution. Run `python3 scripts/forward-local-k8s.py` to keep the existing localhost
API ports connected to these services. The supervisor reselects the service after
a forwarded Pod exits, and owns only its own port-forward processes. It does not
start service JVMs or modify deployment replica counts. The host JVM launcher
remains available as an alternative runtime mode.

The recovery preserves the existing per-service databases, broker and object store,
and only changes this project's namespace and listeners. Current readiness and
resource computation must be verified again after the recovery; previous Ready
snapshots did not establish sustained capacity under the duplicate workload.

K8s-only recovery brought all ten Pods to Ready with zero restarts at the recorded
checkpoint; first replica startups took 6.16–15.84 seconds. The subsequent resource
workflow passed 14 owner-API checks, and the final two-Pod comparison passed 6 checks
including the same source-bound resource assessment. Logs are
`/private/tmp/msc-k8s-pod-only-restore.log`,
`/private/tmp/msc-k8s-resource-workflow.log`, and
`/private/tmp/msc-k8s-resource-verification-final.log`.

A live `kubectl` listener can outlive its selected Pod without immediately exiting.
The forward supervisor therefore probes tunnel liveness as well as child exit,
and replaces only its own unresponsive child. An explicitly stopped child
recovered in 6.624 seconds; a living but deliberately stalled child recovered in
19.728 seconds. Evidence is in `.local/k8s/forward-recovery-verification.json` and
`.local/k8s/forward-stall-recovery-verification.json`. These checks establish local
tunnel recovery, not production service availability or load capacity.
