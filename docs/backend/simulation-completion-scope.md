# Completion scope for the simulator backend

The current accepted target is the [V1 functional review release](v1-functional-scope.md).
On 2026-09-12 the user explicitly narrowed the goal to a version where every main function can
be exercised and inspected; flight-dynamics precision and frequently changing algorithms may
be improved later. This direction supersedes the earlier, stricter numerical completion sequence.

Keep the existing real APIs, persistent service-owned data, independent MSA deployments and
local K8s runtime. Connect the user-facing simulation flow from observation request through
planning, schedule/commands, spacecraft/ground simulation, acquisition and a synthetic product.
Use existing or simple approximate algorithms, label their scope, and provide a guided review.
Continuous-exposure proofs and precision attitude validation are no longer V1 release blockers.

Execution remains SIMULATION, with explicit request/schedule/command/product identities and
existing authorization. Public orbit data remains distinct from telemetry and hardware specs;
synthetic previews remain distinct from Earth imagery. Deferred scientific validation must not
be reported as performed. See the V1 scope for the bounded acceptance checklist and deferred work.
