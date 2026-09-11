# AI Risk Levels

Choose the highest level that applies. Risk classification controls model strength, context breadth, and review gates.

Model names are routing defaults, not permanent policy. Risk gates are the stable policy.

| Class | Meaning | Typical MSC examples | Default builder | Independent review |
|---|---|---|---|---|
| R0 | Mechanical; no behavioral change | typo, rename, formatting, generated docs correction | GPT-5.6 Luna | CI; human merge policy |
| R1 | Local behavior with small blast radius | validation, isolated endpoint, local adapter, simple test | GPT-5.6 Terra | fresh Codex optional |
| R2 | Domain behavior or persistent contract inside one service/context | domain invariant, state machine, schema, API contract, retry/idempotency behavior | Terra; escalate to Sol | fresh Codex required; Claude selectively |
| R3 | System-level or operational behavior across boundaries | cross-service flow, scheduler, Kubernetes/runtime design, simulator integration, deployment behavior | GPT-5.6 Sol | fresh Codex + Claude + human required |
| R4 | Mission/safety/correctness/security critical | command/control, mission execution, time/orbit/frame math, security boundary, destructive migration, production rollback logic | Sol; Astra only when justified | fresh Codex + Claude + human required |

Human merge authorization is required for every class. The table describes additional review gates.

## R2 Claude trigger

Claude review is required for R2 when any of these apply:

- concurrency or race conditions,
- database/schema migration,
- state-machine invariant with non-trivial failure states,
- public API/protocol compatibility,
- cryptographic/security-sensitive code,
- mathematical/scientific correctness that is not fully captured by tests.

Otherwise the fresh Codex review is sufficient for the AI-independent gate.

## Escalation rule

Do not escalate because a stronger model exists.

Escalate only when at least one concrete condition exists:

- the current model cannot explain or fix a failing test after a bounded attempt,
- the task unexpectedly crosses service/domain boundaries,
- correctness depends on non-trivial mathematical or temporal reasoning,
- repository evidence conflicts with the Issue or architecture,
- a reviewer identifies a MAJOR/BLOCKER that requires broader reasoning.

When escalating, pass the compact state:
Issue + changed diff + failing evidence + relevant architecture.
Do not pass the whole prior conversation.

## R4 prohibition

R4 work must not be merged based only on AI review.
AI may implement and review, but a human owns the final decision.
