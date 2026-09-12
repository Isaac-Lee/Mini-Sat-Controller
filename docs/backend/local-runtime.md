# Local backend runtime

Prerequisites: Java 21, Python 3 (bootstrap/verification scripts only), Docker,
and the repository Maven wrapper. All service code and flight calculations run
in Java. No public cloud resources are created.

## Build and infrastructure

```sh
./scripts/dev-env.sh
./mvnw verify
docker compose --env-file .local/msa.env -f deploy/local/compose.yaml up -d --wait
python3 scripts/fetch-orekit-reference.py
```

`verify` uses ephemeral PostgreSQL/RabbitMQ Testcontainers. For Docker Desktop
on macOS, set `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` when required by
Testcontainers. The local compose project is `msc-local`; its service-owned
persistent databases and object data remain in named volumes. Development
credentials are randomly generated once in ignored `.local/msa.env`; preserve
that file when reusing existing volumes.

## Current local K8s runtime

The active verification environment runs the nine services in the dedicated `msc-local`
kind cluster, with two Planning replicas. Local API ports are provided by:

```sh
python3 scripts/forward-local-k8s.py --kubeconfig .local/k8s/kubeconfig
```

The supervisor binds only localhost, refuses occupied ports, and reconnects its own
port-forward children after Pod replacement. Stop it before switching those ports
back to host JVMs. The current environment has no duplicate host service JVMs.
This change followed repeated startup/liveness failures while both service fleets
were running concurrently; PostgreSQL/RabbitMQ/MinIO volumes and service data were
preserved. Kubernetes setup is documented in [the deployment guide](../../deploy/k8s/README.md).

## Alternative host JVM runtime

Run each in its own terminal, with `JAVA_HOME` pointing to Java 21:

```sh
./scripts/run-local-service.sh mission-definition 8104
```

```sh
export MSC_OREKIT_ARCHIVE="$PWD/.local/orekit/3e376b326373467647b1e246ebb083cd9e57cd68/time-frames.zip"
export MSC_OREKIT_SHA256=ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1
./scripts/run-local-service.sh flight-dynamics 8103
```

The runner copies each built JAR to an immutable SHA-256 path under `.local/runtime`
before launch, so subsequent Maven builds cannot replace a running application.

Nine service executables are implemented at this checkpoint: mission-definition,
flight-dynamics, tasking, reference-data, ground-operations, simulator, monitoring, anomaly and planning. The
runner knows the planned service names but cannot launch unimplemented services.
Planning provides [durable input collection](planning-inputs.md), [recorded runs](planning-runs.md) and [resource assessments](planning-resources.md); schedule commitment is still pending.
Start it with `scripts/run-local-service.sh planning 8102`; run
`python3 scripts/verify-planning-inputs.py` with the prerequisite services active.
All listener and compose ports are bound to localhost. Basic authentication is
explicitly local simulation configuration; configured OIDC is the default
non-local security mode. Local S3 credentials are development-only root credentials;
production bucket-scoped identities remain deployment work.

Additional terminals for the request intake segment:

```sh
./scripts/run-local-service.sh tasking 8101
./scripts/run-local-service.sh reference-data 8105
```

After both readiness endpoints return UP:

```sh
python3 scripts/verify-tasking.py
```

For the explicitly local two-process consistency check, temporarily start another
`tasking` instance on 18101 and run `python3 scripts/verify-tasking-replicas.py`.
Stop that extra process after verification. See [request/reference API and scope](tasking-and-reference.md).

## Verify the reference adapter and live API

```sh
export MSC_TEST_OREKIT_ARCHIVE="$PWD/.local/orekit/3e376b326373467647b1e246ebb083cd9e57cd68/time-frames.zip"
export MSC_TEST_OREKIT_SHA256=ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1
./mvnw -pl msc-orbit-adapter -am -Dtest=OrekitReferenceFramesIT,OrekitAccessPredictorIT -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/verify-flight-dynamics.py
python3 scripts/verify-access-predictions.py
```

The API verification creates explicitly synthetic orbit solutions and predicted
artifacts in the local development databases/bucket. It emits IDs and hashes,
never credentials. `/actuator/health/readiness` is public; domain APIs require
authentication, internal APIs require the SERVICE role, and mutations have
additional role checks. Most POST mutations require `Idempotency-Key`; public orbit registry refresh/resume
uses the documented durable claim/cooldown semantics.

Flight Dynamics endpoints:

| Method | Path | Result |
| --- | --- | --- |
| POST | `/internal/orbits` | Immutable EME2000 Cartesian solution, SI units, explicit provenance |
| GET | `/internal/orbits/{id}` | Exact initial solution |
| POST | `/internal/predictions` | Ephemeris manifest for a pinned solution and TAI horizon |
| GET | `/internal/predictions/{id}` | Stored model/reference/object provenance |
| GET | `/internal/predictions/{id}/samples` | Streamed JSON prediction and ground track from S3 |
| POST | `/internal/access-predictions` | Continuous geometric point-imaging or contact windows |
| GET | `/internal/access-predictions/{id}` | Exact stored access query, model and reference provenance |
| GET | `/internal/orbit-designations/{spacecraftId}` | Versioned operational selection pointer |
| POST | `/api/orbit-designations/{spacecraftId}` | ADMIN selection with expected version, decision reference and idempotency |
| POST | `/internal/time/utc-to-tai` | UTC history conversion, including leap seconds |

Equivalent authenticated `/api/orbits` and `/api/predictions` endpoints exist
for operator use, with ADMIN required to register orbit solutions. Orbit designation requires an explicit ADMIN decision; it changes the selection
pointer without mutating orbit history. A designation is not mission qualification,
pass reservation or command authority. Access predictions use WGS84 geometry and
a radial-nadir pointing constraint, return only windows long enough for the requested
minimum duration, and search at most 24 hours per request. They do not establish
full AOI coverage, weather suitability, resource feasibility or ground booking.

Stop only the process terminals and `msc-local` project when finished. Do not
remove volumes to resolve a service restart; persistence is part of the test.

See [public NORAD orbit collection](public-orbits.md) for SPACEEYE-T1/63229 and
[ground booking](ground-booking.md) for ports 8106/8114 and reservation verification.

[Monitoring](monitoring.md) runs on 8109 and verifies Simulator telemetry events,
versioned estimates and optional two-process consistency on 18109.

[Safety and Anomaly](safety-and-anomaly.md) runs on 8110 and verifies durable
freezes, operator recovery and telemetry-silence detection.
