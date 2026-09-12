# V1 request-bound simulation command execution

Continue from [V1 schedule selection](simulation-schedule-v1.md). This flow uses the existing
independent Control and Simulator services. It delivers semantic commands to a simulation
scenario; it does not use an RF or physical spacecraft adapter.

## Operator flow

1. Prepare the load from the committed schedule and grant the required operator approvals.
2. Create a Simulator scenario with the same mission and exact time correlation. Its initial
   virtual tick must precede the load's commit deadline. Publish the explicit simulation command
   authority policy and enable the safety latch using its configured operator approvals.
3. `POST /api/command-loads/{loadId}/simulation-release` with `Idempotency-Key` and
   `{ "scenarioId": "uuid", "checksum": "prepared checksum", "reviewReference": "review" }`.
   This stores the reviewed load and source checks; it does not send the command.
4. `POST /api/command-loads/{loadId}/simulation-dispatch` sends the immutable released load.
   The request has no body. Repeated calls reconcile or replay the same delivery.
5. Advance the Simulator virtual clock. Configure its link and request ACK/reconciliation
   reception. Lost ACK remains UNKNOWN until evidence is actually received.
6. After Control receives the execution event,
   `POST /api/command-loads/{loadId}/simulation-execution/reconcile` with `Idempotency-Key`
   binds it to the exact released load and Simulator ledger. Read the saved result from
   `GET /api/command-loads/{loadId}/simulation-execution`.

Release and delivery require ADMIN or OPERATOR. Binding reads/reconciliation also permit the
internal SERVICE role. Requester interaction preference does not grant commanding authority.

## Evidence and retries

Release verifies the scenario's mission/clock, current schedule, command authority/approvals,
safety latch, and each assignment's current request revision and V1 decision. Fresh delivery
repeats those checks and verifies the deadline after the owner reads. This is the V1 functional
simulation workflow, not a distributed production authorization fence or precision FD clearance.

A durable attempt precedes outbound I/O. A stable Simulator idempotency key and immutable
submission prevent duplicate loads when an HTTP acknowledgment or a local result write fails.
Transport uncertainty is saved as UNKNOWN. Before retrying, Control reads the Simulator ledger;
an already accepted matching load is recorded as ACCEPTED even if the request was cancelled
later. That is historical delivery evidence, not permission for a new command. An authoritative
missing ledger is required before another delivery attempt, which repeats current checks.

Execution reconciliation compares scenario/craft/load identities, released content, submission
hash, ledger version/hash and per-command effects. The saved binding records original request
IDs. `SIMULATION_EFFECTS_CONFIRMED` means all modeled effects were applied;
`SIMULATION_EFFECTS_REJECTED` retains observed rejection/unsupported outcomes. Original received
events remain immutable. Synthetic image bytes are separate S3 artifacts, not Earth imagery.

## Guided verification

```sh
python3 scripts/verify-planning-search.py --with-runs --with-camera \
  --with-simulation-commit --with-simulation-dispatch --timeout-seconds 180
```

This extends the real request/candidate/schedule/approval scenario with Control release and
delivery, IMAGE execution, a lost ACK and reconciliation, bound request IDs and a synthetic S3
payload. It cancels the test request after verification. Ground downlink and request-product
fulfillment are the next integration stage; this command must not yet be described as proving
that complete downstream flow.

PostgreSQL tests cover release/delivery separation, immutable retries, changed authority and
cancelled requests, lost ACK reconciliation, recovery after a delivery-result outbox rollback,
and exact execution ledger binding. Existing command compilation and Control HTTP/RabbitMQ
regressions are included in the focused packaging gate.

Focused packaging verification passed 16 tests on 2026-09-12 at 18:53 KST (PostgreSQL,
HTTP, RabbitMQ and compiler checks). This is not a full-reactor re-verification.

Deployed HTTP verification passed on `msc-spacecraft-control:755027cf7469a09dbb49e519`.
Request `dfc5480e-e72f-4463-b5a2-901c057a6f20` generated load
`v1-9feaea4c5ede4b9486f1d66808eee154`; scenario `244d3ce4-d198-4367-804e-d26b1d4602b3`
accepted it, applied IMAGE, returned UNKNOWN for the lost ACK, and produced received execution
evidence subsequently bound as SIMULATION_EFFECTS_CONFIRMED to the same request.
Synthetic payload `978cebb4daf3eebb471569c099437c13aeb88c8f4ddb8cc5d874c91d68f53c46`
was persisted with SHA-256 `dd0fcb4dd730b4a74dc48d68ef1b0423c2afceb9afac9ffcb653757a7aaaa0a0`.
The verifier cancelled the request after success; these are historical verification IDs.
