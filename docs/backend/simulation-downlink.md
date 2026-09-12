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
