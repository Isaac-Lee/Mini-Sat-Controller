# Simulation time correlation (Slice A)

An explicit, ADMIN-published, versioned SIMULATION onboard tick &harr; TAI
correlation for one spacecraft, owned by Mission Definition and keyed by
`spacecraftId`. This is **Slice A** of a two-slice plan
(`.local/claude-delegation/opus-simulator-execution-plan.md`, "Revision 2"
section, R3/R6): it defines the conversion function and its exact validity
window only. **It does not implement command execution, an onboard ledger,
any handler, or any physical effect.** Slice B (a separate, not-yet-started
piece of work) is the onboard command receipt/execution ledger that will
consume this correlation; it is out of scope here and nothing in this slice
should be read as delivering it.

## Why this exists

`msc.domain.shared.Ids.TimeCorrelationId` appears elsewhere in this codebase
only as an identity string used for **binding checks** —
`msc.domain.spacecraftcontrol.CommandLoad`,
`CommandReleasePolicy.Binding`, and
`CatalogContracts.MissionProfile.timeCorrelationId`. Nothing, anywhere,
publishes an actual ticks &harr; TAI conversion *function*. So
`msc.domain.time.OnboardTime.ticks()` has no defined meaning today — there is
no way to say what TAI instant a given onboard tick count actually
corresponds to. This slice closes exactly that gap, and is independently
useful before any executor exists.

## Contract (`msc.contracts.SimulationTimeCorrelationContracts`,
`msc-contracts/src/main/java/msc/contracts/SimulationTimeCorrelationContracts.java`)

### `Correlation`

```
Correlation(
    spacecraftId, missionDefinitionVersion, timeCorrelationId, clockPartition,
    taiEpoch, tickEpoch, ticksPerSecond, validInterval,
    environment, approvalReference, provenance)
```

- `timeCorrelationId` is bound, byte-for-byte, against the spacecraft's
  currently stored `CatalogContracts.MissionProfile.timeCorrelationId` at
  publish time — exactly as `missionDefinitionVersion` is bound against
  `MissionProfile.missionDefinitionVersion` everywhere else in this package.
- `clockPartition` must match `OnboardTime.clockPartition()` for any onboard
  time value this correlation is used to convert.
- `taiEpoch` is a `MissionInstant`, checked TAI via `requireTai()`.
- `tickEpoch` is the onboard tick count of the same physical moment as
  `taiEpoch` — the anchor the affine tick &harr; TAI mapping is built from.
  It must be non-negative, matching `OnboardTime`'s own invariant that a
  tick count is never negative.
- `ticksPerSecond` — **an explicit SIMULATION representation choice, not a
  real hardware limit** — must be a positive integer, at most
  `Correlation.MAX_TICKS_PER_SECOND` (one billion), for which
  `MAX_TICKS_PER_SECOND % ticksPerSecond == 0`. That restriction is what
  makes one tick equal to exactly `MAX_TICKS_PER_SECOND / ticksPerSecond`
  nanoseconds — an exact integer — so every conversion is exact in
  `MissionInstant`'s nanosecond representation with **no rounding or
  floating-point drift anywhere**. A real onboard oscillator would not get
  this courtesy; this is a named simulation convenience, and the javadoc
  says so explicitly.
- `validInterval` is a half-open `[start, end)` `msc.domain.time.TimeWindow`
  in TAI: the reused, already-half-open domain interval type, rather than a
  new one. Both conversion directions reject an instant outside it, rather
  than extrapolating.
- `environment` must be exactly `"SIMULATION"`.
- `approvalReference` and `provenance` are required free-text administrator
  inputs, exactly as elsewhere in this codebase's mission-definition
  contracts.

### Conversions — the heart of this slice

Both directions are **pure instance methods on `Correlation`**, not HTTP
endpoints (see "API" below for why). Both require the caller to pass the
`timeCorrelationId`/`clockPartition` it expects to be converting under;
either mismatch is rejected. **There is no "latest" resolution** — a caller
must have already resolved the exact pinned `Correlation` version it intends
to use (via the exact-version read) before calling these.

- **`tickToTai(requestedTimeCorrelationId, requestedClockPartition, tick)`**
  - Rejects a negative `tick` **explicitly, before any arithmetic** — its
    own named check, not a side effect of the overflow guard or the
    validity-window check. `OnboardTime`'s own compact constructor already
    forbids a negative tick count; a correlation that converted one anyway
    would hand back a TAI instant for an onboard time that can never exist.
    A positive `tickEpoch` paired with a broad valid interval would let
    `tick = -1` both avoid overflow and land inside the window, which is
    exactly why this cannot be left to either of those other checks — see
    `negativeInputTickRejectedExplicitlyEvenWhenItWouldOtherwiseLandInsideTheWindow`
    in the contract test.
  - `elapsedTicks = tick - tickEpoch` (via `Math.subtractExact`) may be
    negative — a tick before the epoch.
  - The nanosecond offset (`elapsedTicks * nanosPerTick()`, via
    `Math.multiplyExact`) is added to `taiEpoch` with correct carry/borrow
    across the second boundary using `Math.floorDiv`/`Math.floorMod`, which
    — unlike truncating division/remainder — give the mathematically
    correct, always-nonnegative fractional remainder for a negative
    numerator.
  - Rejects (throws `IllegalArgumentException`) if the result falls outside
    `validInterval`.
  - Any arithmetic overflow throws `ArithmeticException` — never a silent
    wrap.
- **`taiToTick(requestedTimeCorrelationId, requestedClockPartition, instant)`**
  - Rejects an `instant` outside `validInterval`.
  - Computes the exact nanosecond difference from `taiEpoch` with checked
    arithmetic.
  - Rejects (never rounds) if that difference is not exactly aligned to the
    tick grid (`nanos % nanosPerTick() != 0`).
  - Divides exactly, adds `tickEpoch`, and rejects a negative resulting tick
    value.
  - Any arithmetic overflow throws `ArithmeticException`.

### `Publish`

`Publish(expectedVersion, correlation)` — CAS envelope, identical shape to
`AgilityContracts.Publish`.

## API (`msc.services.missiondefinition.SimulationTimeCorrelationApi`)

Mirrors `AgilityApi`/`OperationResourceApi` exactly in shape:

- `POST /api/simulation-time-correlations`: `{expectedVersion, correlation}`,
  `hasRole('ADMIN')`, requires `Idempotency-Key`. Inside one
  `store.idempotent` + `store.lock("simulation-time-correlation:" +
  spacecraftId)` + `store.require("mission", ...)` transaction:
  1. `correlation.missionDefinitionVersion` must equal the stored mission's
     `missionDefinitionVersion` (400 otherwise).
  2. `correlation.timeCorrelationId` must equal the stored mission's
     `timeCorrelationId` (400 otherwise) — the second mission-binding check
     this slice's brief specifically required, distinct from (1).
  3. A stale `expectedVersion` is rejected (409). Otherwise `store.create`/
     `store.update`, the immutable history write, and a
     `SimulationTimeCorrelationPublished` outbox event happen in the same
     transaction. A duplicate request with the same `Idempotency-Key` and
     byte-identical body returns the stored response, with no extra version
     and no extra event; the same key with a **changed** body is a 409
     (this is `StateStore.idempotent`'s existing, unmodified fingerprint
     behavior — not new logic in this class).
- `GET /api/simulation-time-correlations/{spacecraftId}` and
  `/internal/simulation-time-correlations/{spacecraftId}`: current version,
  `hasAnyRole('ADMIN','OPERATOR','SERVICE')`.
- `GET /api/simulation-time-correlations/{spacecraftId}/versions/{version}`
  and the equivalent `/internal/...` route: immutable exact version, same
  roles — so a future Control service and a future Simulator can each pin
  the exact version they used.

**This class deliberately exposes no HTTP conversion endpoint.** `tickToTai`
and `taiToTick` are plain methods on the already-resolved `Correlation`
object; a caller resolves the exact pinned version via the reads above and
calls the conversion directly. Adding an HTTP conversion endpoint was not
requested by this slice's scope and would invite exactly the "latest
resolution" ambiguity the conversions are designed to refuse.

`SimulationTimeCorrelationPublished` is published to the outbox exactly like
every other owner-input event in this package; it is **not** added to
`EventTopology.ROUTES` by this change (out of scope — root/Codex owns that
file), so it currently reaches only the catch-all `"mission-projection"`
queue (bound on `"#"`), the same as any other as-yet-unrouted event type in
this codebase.

## No correlation is guessed

No tick rate, epoch, or validity window is inferred from NORAD identity or
any public orbit. SPACEEYE-T1 / NORAD 63229 is public GP orbit tracking
only, never a source of clock truth. Every field is an explicit
administrator input, checked structurally by the contract's compact
constructor and, for the two mission-binding fields, checked against the
stored `MissionProfile` at publish time.

## What this slice explicitly does not deliver

No command execution, no onboard ledger, no command handler, no physical
effect of any kind. `msc.ports.SpacecraftLinkPort` still has no
implementation and no release use case exists in v0.1 — none of that changes
here. This slice supplies one thing only: a rigorously exact, versioned,
pinned tick &harr; TAI conversion function for a declared SIMULATION
environment. The onboard command receipt/execution ledger that will consume
it (Slice B) is separate, not-yet-started work.
