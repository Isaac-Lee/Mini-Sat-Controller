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
Reference-archive collection is opt-in: `render-k8s.py --reference-archive-writer` also mounts
the volume's `snapshots/` subPath read-write into reference-data. This needs a writable RWX
volume, or co-scheduled pods, with `snapshots/` created in advance. A read-only kind mount does
not qualify. See [reference archive](../../docs/backend/reference-archive.md).

Resource requests/limits are initial local validation settings, not production
capacity measurements. Database pool size must be budgeted across all replicas,
including rollout surge. The default render keeps explicit replica counts; HPAs are
opt-in with `--hpa` (see [Autoscaling and load smoke](#autoscaling-and-load-smoke)).

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

## Autoscaling and load smoke

`render-k8s.py --hpa` adds an `autoscaling/v2` HPA (min 1, max 2, CPU 80% of the
250m request, 60 s scale-up and 300 s scale-down stabilization) for acquisition,
planning and tasking only, the services with recorded multi-replica checks
(`verify-k8s.py`, `verify-tasking-replicas.py`). Their Deployments omit
`spec.replicas` so the HPA owns it; the other nine services keep one replica because
their workers and safety gates have no concurrent-replica verification. The fixed
two-replica assertions in `verify-k8s.py` apply to the default render, not `--hpa`.

With `--hpa` the renderer pins `MSC_DATABASE_POOL_SIZE` on every Deployment and
refuses to render when the worst case exceeds `--database-max-connections`
(default 100, the PostgreSQL default; all service databases share one local server).
Worst case = (9 fixed Pods + 3 × 2 HPA Pods + 12 surge Pods) × pool. The 12 surge
Pods cover one apply rolling every Deployment at once. Pool 2 (the local runtime
value) needs 54 connections; pool 6 needs 162 and the platform default 8 needs 216,
which are both rejected. Pool sizes below 1 are rejected too. Local render:

```sh
python3 scripts/render-k8s.py --images .local/service-images.json --hpa \
  --database-pool-size 2 --local-orekit-host-path /msc-orekit \
  --local-metrics-server > .local/k8s/services.json
```

`--local-metrics-server` appends metrics-server v0.7.2 with
`--kubelet-insecure-tls`, labelled `msc.local-only: "true"`. It is only for the
project-owned kind node. Never apply it to a shared or production cluster, and skip
it where the cluster already runs metrics-server (k3s/Rancher Desktop).

`scripts/load-smoke.py` sends a bounded number of read-only GETs (default 600,
concurrency 4, max 10000/16). Request *i* goes to target *i* mod N over the localhost
ports of `forward-local-k8s.py`. It records p50/p90/p99/max latency, error rate and
status counts per target, plus first/peak/last `kubectl top pods` CPU and memory, under
`environment: local` with the date and machine. It reports no throughput rating.
`/internal/*` targets are sent as the `service` user (SERVICE role) and `/api/*` as
`admin`. Planning and acquisition have no id-free read endpoint, so target them with
`--target planning:8102:/internal/planning/runs/<id>` using an existing id.
`python3 scripts/test_k8s_scaling.py` checks the renderer and load-smoke logic offline.

### Estimates to compare against (not yet measured)

| Item | Estimate | Basis |
| --- | --- | --- |
| Idle CPU per service Pod | well below the 250m request | request sized for startup headroom, not observed |
| Steady memory per Pod | under 512Mi limit (768Mi flight-dynamics); heap ≤ 60% of limit | `MaxRAMPercentage=60` |
| Read-smoke latency (local, concurrency 4) | p99 under 1 s, error rate 0 | no prior measurement; to be replaced |
| HPA scale-up | only when sustained average CPU > 200m per Pod | 80% of 250m request |
| DB connections at HPA max during a full rollout (pool 2) | ≤ 54 | renderer budget above |

### Measurement status: environment local, 2026-11-07, not measured

Machine: Apple M3, 8 CPUs, 16 GiB, macOS 27.2. Nothing was measured, so no latency,
error-rate or CPU/memory numbers are recorded. At the time, Docker Desktop (which
hosts the `msc-local` kind cluster) was stopped, its kubeconfig endpoint refused
connections, and no local PostgreSQL, RabbitMQ or MinIO listener was running.
Rancher Desktop's k3s node was up with metrics-server, but it allocates 2 CPUs.
The 12 services request 3 CPUs together, so they cannot be scheduled there.
`render-k8s.py --hpa --database-pool-size 2 --local-metrics-server` output passed
`kubectl apply --dry-run=client --validate=strict` against that k3s API (v1.34).
This is schema validation only. No object was applied, and HPA behavior, scaling and
the DB-pool budget remain unverified.

To measure: start Docker Desktop and the local infrastructure, then deploy with the
`--hpa` render above. Run `forward-local-k8s.py` and confirm
`kubectl --kubeconfig .local/k8s/kubeconfig top pods -n msc` returns data. Then run
`python3 scripts/load-smoke.py --output .local/k8s/load-smoke.json`. Also record
`kubectl get hpa -n msc` before and after the run. Replace this subsection with the
recorded numbers, the date and the machine, and compare each with the estimates
table. These are local numbers only and never production capacity.
