# ADR-0014 — Operator-initiated simulated ground-segment steps

Status: Proposed (2026-11-07)

## Context

The V1 simulation flow has steps that stand in for the physical ground segment: receiving
acknowledgement/reconciliation telemetry for a delivered command load, creating a downlink plan
and receiving downlinked bytes. The simulator exposes them only as SERVICE-role `/internal/...`
endpoints, and `scripts/verify-*.py` call them with SERVICE credentials. An operator console must
not hold SERVICE credentials, so it could not complete a new simulated mission.

Alternatives considered: a simulator-side automatic worker (hides each step and its UNKNOWN
outcomes from the operator, needs a new scheduler) and mapping browser requests to the SERVICE
role in the development proxy (fakes the security boundary).

## Decision

The service that owns each step exposes an OPERATOR/ADMIN endpoint that relays exactly one
attempt to the simulator through `ServiceHttp`:

- spacecraft-control: `POST /api/command-loads/{id}/simulation-reception` (TT&C reception of a
  released load).
- ground-operations: `POST /api/bookings/{id}/simulation-downlink-plan` (payload ID derived
  server-side) and `POST /api/simulation-downlinks/{planId}/receive`.

Each relay refuses anything that is not a SIMULATION release/scenario, returns the simulator's
answer unchanged (including `UNKNOWN` with its reason), never manufactures evidence, and scopes
the simulator idempotency key to the caller's per-attempt key so a retried attempt is
deduplicated while a later attempt after `DELAYED`/`DISCONNECTED` is evaluated again.

## Consequences

The SERVICE-only simulator endpoints stay unchanged and remain the single place that models the
simulated ground segment. These endpoints are simulation controls, not RF or a real ground
station; they are labelled SIMULATION in their docs and in the console. Removing the SIMULATION
check, or reusing these paths for hardware, requires a new decision.
