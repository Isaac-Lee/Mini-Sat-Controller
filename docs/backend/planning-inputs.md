# Planning intake and simulation agility models

Planning is an independent service on local port 8102 with its own PostgreSQL
owner. It consumes accepted/invalidation facts through RabbitMQ and durably claims
work by request revision and priority. Database row locks, leases and revision
fences prevent duplicate workers or cancelled/older revisions from publishing a
current result. Input owner calls occur outside database transactions. Each completed
input attempt is immutable; the current work row points to its latest attempt.

This stage collects inputs and invokes [automatic point geometry search](planning-search.md).
It does not yet calculate fully validated candidates, commit schedules,
book downlinks or authorize transmission. Missing capabilities remain visible as
`WAITING_INPUTS`; they are not successful feasibility results.

## Source collection

Each asset captures its mission profile, approved activity, designated orbit with
explicit Cartesian/public-GP kind, EOP/leap reference context, current Monitoring
estimate, synchronous Anomaly check and approved simulation agility model. Captured
weather inputs come from [explicit simulation forecasts](weather-inputs.md) covering
the full request AOI and collection horizon. Other captured
JSON and its canonical fingerprint are retained with the source service/path.
The GROUND_SCHEDULE input pins a [local allocation snapshot](ground-availability.md);
provider-confirmed reservation is still required later.
[Propellant estimates](propellant-inputs.md) pin a fresh Monitoring source and
approved synthetic model with explicit uncertainty/loss/propagation limits.
The fingerprint describes the captured JSON representation, not raw HTTP bytes.
Only source versions actually obtained are recorded. Historical attempts remain
readable after request revision/cancellation and model changes.

Agility models belong to Mission Definition and bind its configured
`missionDefinitionVersion`. Supported fields are maximum off-nadir angle (0..60,
exclusive zero), positive bounded slew rate and nonnegative settling time. These
are explicitly `SIMULATION` models with an approval reference; no SPACEEYE-T1
hardware specification is inferred from its public orbit. A rate parameter alone
does not prove an attitude transition feasible: initial/final attitude and activity
sequence validation are still required in the scheduling engine.

## APIs

All paths require authentication. Internal paths require SERVICE. Model publication
requires ADMIN and `Idempotency-Key`; reads permit ADMIN, OPERATOR or SERVICE.

- `POST /api/agility-models`: `{expectedVersion, model}`; first version expects 0.
- `GET /api/agility-models/{spacecraftId}`: current versioned state.
- `GET /api/agility-models/{spacecraftId}/versions/{version}`: immutable exact version.
- Equivalent `/internal/agility-models/...` GET routes are available.
- `GET /api/planning/requests/{requestId}`: owner or elevated operator summary;
  another requester's request is hidden with 404.
- `GET /internal/planning/requests/{requestId}`: service work summary.
- `GET /api/planning/input-attempts/{id}`: ADMIN/OPERATOR/SERVICE detail, also
  available under `/internal/planning/input-attempts/{id}`.

Publication performs mission binding validation and compare-and-set in the same
owner transaction as immutable history and `AgilityModelPublished` outbox creation.
The event wakes waiting planning work. It does not itself claim planning succeeded.
Tasking's SCHEDULED status requires reconciliation with an actual schedule before
Planning can accept it as evidence of a committed assignment.

## Runtime and verification

After building and starting the documented prerequisite services:

```sh
scripts/run-local-service.sh planning 8102
python3 scripts/verify-planning-inputs.py
```

The verifier creates explicitly synthetic catalog/mission/model records, submits a
real Tasking request, waits for broker-driven input collection, checks pinned model
content, privacy and historical reads, revises/cancels its requests and checks old
attempt immutability. It retains its synthetic mission records and writes local
IDs/results under `.local/planning-input-verification.json`.

Default tests exercise lease/revision/invalidation races against PostgreSQL and
mission binding, idempotency, CAS, version history and role enforcement over real
HTTP. Planning caps one collection at 32 assets and checks a 20-second budget
between assets; individual owner calls can overrun that budget. Leases last 120
seconds; work retries after 30 seconds or an input wakeup (minimum five seconds).
These are development bounds, not a fleet throughput or latency qualification.

On 2026-09-12 the default full build passed 95 tests with no failures or skips.
The live verifier passed all nine checks against separate Tasking, Mission
Definition and Planning processes, RabbitMQ and their PostgreSQL owners. Its
synthetic mission used agility version 2; the completed attempt retained that
version after request revision/cancellation. An initial test run was interrupted
after Docker assigned a test PostgreSQL container the local MinIO port; a fresh
full run succeeded without modifying the local storage service.

## Remaining integration

Power forecasts, sensor coverage, provider-confirmed ground reservations,
sequence/agility evaluation, resource timelines, atomic schedules and final release
checks still require integration. Point geometry and fresh telemetry alone cannot complete these gates.
