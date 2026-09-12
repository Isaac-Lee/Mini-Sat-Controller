# Independent Control evidence intake

`msc-spacecraft-control-service` is an independent Spring Boot service with its own PostgreSQL
database (`msc_spacecraft_control`), inbox/state transaction and Kubernetes Deployment/Service.
The local API forward is port 8107. It consumes the existing `msc.spacecraft-control.v1` queue.

`SpacecraftExecutionObserved` is decoded as the explicit SIMULATION observation contract,
checked against the envelope spacecraft identity, and preserved with source event ID,
event-creation time, Control reception time and canonical observation hash. The key is
scenario UUID plus load ID, so separate simulated scenarios cannot overwrite one another.
Same-event deliveries are deduplicated by the inbox; same-content observations under another
event ID retain one immutable evidence record. Conflicting content rejects the event and
rolls back inbox acceptance without overwriting the original record.

The initial binding status is **UNBOUND_SIMULATION_EVIDENCE**. Command preparation and the
release gate are not implemented here yet, so received simulation facts must not become
approved loads or physical spacecraft execution confirmations. `ScheduleVersionCommitted`
and other routed inputs are preserved as `deferred-control-input` records in the same inbox
transaction, ready for the remaining workflow instead of being acknowledged and discarded.

ADMIN/OPERATOR/SERVICE reads use
`GET /api/simulation-execution-evidence/{scenario}/{load}`. SERVICE may also use the
corresponding `/internal` path; global internal-route restrictions remain unchanged.
Requester reads are denied. No write/import endpoint can bypass the broker intake.

## Validation

Three `ExecutionEvidenceApiIT` tests passed with actual RabbitMQ, PostgreSQL and Spring HTTP:
broker delivery/retry and read roles, conflicting observation/envelope rollback, and retention
of deferred schedule input. Log: `/private/tmp/msc-control-evidence.log`.

After deployment, `scripts/verify-control-evidence.py` ran a fresh simulator command/delivery
fixture, then passed five Control checks: actual broker-to-Control delivery, exact observation
preservation, retained unbound status, allowed read roles and requester denial. No test double
or direct cross-service database access was used in this deployed flow. Evidence:
`.local/control-evidence-verification.json`, log `/private/tmp/msc-control-live.log`.
This proves the evidence intake path, not operational release, physical RF commanding or the
remaining full mission lifecycle.

## Initial runtime budget

Starting requests are 250 millicores and 256 MiB; limits are 2 CPUs and 512 MiB, with the
existing graceful shutdown, bounded connection pool (2), probes and hardened container defaults.
The initial workload assumption is low-rate intake (up to one modeled observation per second,
at most 100 command results per observation) and diagnostic reads. This is a starting estimate,
not a measured sustained throughput guarantee.

A 2.975-second synthetic workflow sample measured about 236 millicores averaged across that
interval and cgroup memory from 235.0 to 238.4 MiB. The earlier startup snapshot was 245.7 MiB.
These observations fit the initial requests in those samples, but are too short to stabilize
the budget or establish burst capacity. Evidence is `.local/control-resource-sample.json`.
Qualification still requires sustained representative event/read traffic, latency/backlog and
restart measurements, and independent replica behavior. No unrelated cluster workload was changed.
