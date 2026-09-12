# Simulated payload downlink binding

SERVICE can call `POST /internal/simulation/downlinks` to bind one stored onboard payload
to one pending DOWNLINK command and a confirmed simulator station booking. The request names
scenario, load, command, payload and booking IDs; all content is loaded from owned immutable
records. Callers cannot supply a fabricated payload manifest or command catalog.

Binding must happen before the command's start tick, after payload generation. The payload
must belong to the same scenario and retain SIMULATION/ONBOARD scope. The booking must belong
to the same spacecraft and contain the entire correlated command interval. Payload bytes
must fit both the command's declared downlink rate/duration and the station's rate during
that interval. A booking's total allocated bytes cannot exceed its requested decimal-MB
volume. Each command accepts one payload; larger multi-payload transfer orchestration remains
future work. There is no implicit splitting, truncation or time rounding.

The booking lock serializes byte allocation, and the scenario lock serializes command state.
The immutable plan and booking byte allocation commit with the idempotency response in one
transaction. A new key with the identical command binding returns the original plan; a
changed binding for the same command conflicts. An exhausted booking cannot be reused for
another command. `GET /internal/simulation/downlinks/{id}` returns the plan to SERVICE.
Its ID is the canonical fingerprint of `[scenarioId, commandId]`.

This is onboard transfer intent, not a receiver receipt or Control release authorization.
No reception event is emitted. The subsequent receiver must recheck cancellation, actual
DOWNLINK effects, current connection/fault state and byte/hash integrity before recording
receipt. Reservation and source objects are retained. Production contact geometry, physical
radio behavior, packet gaps and acquisition completion are not inferred from this binding.

Source-only validation: five test executions passed on 2026-09-12 in
`/private/tmp/msc-simulation-downlink-plan.log`, including four inherited payload persistence
cases and one new real-PostgreSQL binding case. The binding fixture seeds owned scenario,
ledger, payload and booking records; it verifies replay, single immutable allocation,
booking-volume exhaustion, late binding and cancelled booking rejection. It does not yet
prove HTTP role enforcement, the full source-generation path or deployed transfer execution.

## Receiver evidence

With S3 enabled, SERVICE can call `POST /internal/simulation/downlinks/{id}/receive` with
an ACKNOWLEDGMENT or RECONCILIATION channel and idempotency key. The receiver loads the stored
plan and checks the same booking and scenario locks used by allocation, cancellation and
scenario changes. It requires the unchanged confirmed booking, configured connected link,
not-before tick, completed APPLIED command effect and sufficient actual modeled drain bytes.
Command/template/profile/timing must still match the original allocation. Missing link,
disconnection, delay, lost acknowledgement or unobserved/insufficient effects produce UNKNOWN,
not successful reception. RECONCILIATION only bypasses the lost-acknowledgement condition.

The receiver reads the entire bounded S3 object outside DB transactions and verifies exact
byte count and SHA-256. Extra, missing or corrupt bytes cannot create a receipt. It then
rechecks the booking, scenario, ledger and link under their locks; any revision/content change
during the read yields UNKNOWN. The immutable receipt/history, `SimulatedPayloadReceived`
outbox event and idempotency response commit atomically. An outbox failure rolls them back.
A same-key retry retains the original result, including UNKNOWN; a fresh observation attempt
uses a new key. Once a receipt exists it is retained through later cancellation/disconnection
and returned without rereading the object or publishing another event.

`GET /internal/simulation/downlinks/{id}/receipt` returns the receiver record to SERVICE.
It retains plan hash, station, ledger/link revisions, received-at simulation time, size/hash
and the immutable object reference. The station simulator has observed the bytes of that
retained object; this does not create a second independent storage bucket or establish
physical RF/contact behavior. Acquisition must still import the verified source into its own
storage and produce mission-specific products. No request fulfillment is inferred here.

Source validation passed 12 test executions across two classes on 2026-09-12, including eight
inherited payload persistence executions, the allocation case and three new receiver cases
(`/private/tmp/msc-simulation-downlink-reception.log`). Real PostgreSQL tests verify pending
and absent-link UNKNOWN, verified receipt/event uniqueness, preserved UNKNOWN replay,
retained receipts after cancellation, truncated bytes, cancellation during read, and outbox
rollback with same-key retry. Fixtures seed owned ledger completion and mock S3 reads; actual
HTTP roles, full command-to-station transfer and deployed reception remain to be verified.
