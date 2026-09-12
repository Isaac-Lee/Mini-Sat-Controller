# Mission catalog bindings

An explicit ADMIN-published, versioned, multi-activity catalog binding for a
spacecraft, owned by Mission Definition and keyed by `spacecraftId`. It is a
**new, separate** contract and API from `CatalogContracts.MissionProfile` and
`CatalogApi` — neither of those, nor `CatalogApiTest`, is modified by this
change, and every existing single-catalog `MissionProfile` read keeps working
exactly as before.

## Why this exists

`CatalogContracts.MissionProfile` pins exactly one `(catalogId,
catalogVersion)` pair
(`msc-contracts/src/main/java/msc/contracts/CatalogContracts.java:110-111`),
validated at publish in `CatalogApi.java:68` and read by Planning at
`PlanningInputs.java:280-282`. The accepted final flow needs imaging **and**
downlink activity definitions, so one pin is genuinely insufficient. This
binding lets a spacecraft bind several approved catalog references by
explicit, bounded operation role instead of adding a second field, a list, or
any other ad hoc extension to `MissionProfile` itself.

## Contract (`msc.contracts.MissionCatalogBindingContracts`,
`msc-contracts/src/main/java/msc/contracts/MissionCatalogBindingContracts.java`)

- `Role`: a bounded enum — `IMAGING`, `DOWNLINK`, `MANEUVER`. No other role
  may be bound.
- **Role-to-operation mapping is explicit, not a free-form key.** Each `Role`
  declares the exact `CatalogContracts.CommandTemplate.operation` a catalog
  entry bound to it must carry (`Role.operation()`):
  - `IMAGING -> "IMAGE"` — the only operation that exists anywhere in this
    codebase today (`CatalogApiTest.ENTRY` and
    `scripts/verify-planning-inputs.py` both use it for an imaging activity).
  - `DOWNLINK -> "DOWNLINK"` and `MANEUVER -> "MANEUVER"` are **declared-but-
    unexercised simulation contract values**: no catalog entry or fixture
    with those operations exists in this repository yet. Nothing about them
    is invented beyond the label — publishing a binding for either role
    simply requires that some future approved catalog entry declare that
    exact operation string. `MissionCatalogBindingApi` rejects a role bound
    to an entry whose `template().operation()` does not match
    (`roleOperationMismatchRejected` / acceptance check B8).
- `CatalogReference(catalogId, catalogVersion)`: an exact, single catalog
  activity version, never a range or a "latest" pointer.
- `RoleBinding(role, reference)`.
- `Bindings(spacecraftId, missionDefinitionVersion, roles, provenance)`:
  - Duplicate roles are rejected in the record constructor.
  - `IMAGING` and `DOWNLINK` are required; `MANEUVER` is optional, since
    inventing a manoeuvre the simulation does not perform is out of scope.
  - `missionDefinitionVersion` must match the spacecraft's stored
    `MissionProfile.missionDefinitionVersion`, checked by the API exactly as
    `AgilityApi` and `SimulationPlanningModelApi` check it.
- `Publish(expectedVersion, bindings)`.

Nothing in this contract or API is invented: every `(catalogId,
catalogVersion)` is an explicit administrator input, resolved at publish time
against the already-approved activity catalog. No duration, resource,
instrument or operational parameter is derived here — those live only on the
resolved `CatalogEntry`. Nothing is derived from NORAD identity or public
orbit elements: SPACEEYE-T1 / NORAD 63229 is public GP orbit tracking only,
never a hardware or instrument source.

## Precedence against the legacy single-catalog pin

`MissionProfile` keeps pinning one `(catalogId, catalogVersion)` that Planning
currently reads for imaging (`PlanningInputs.java:280-282`). This binding does
**not** create a second, possibly divergent, source of truth for that
activity: **the `IMAGING` role's `CatalogReference` MUST equal the
spacecraft's current `MissionProfile` `(catalogId, catalogVersion)` pin
exactly.** This is enforced by `MissionCatalogBindingApi.publish` (not by the
contract record, which has no persistence dependency to read the legacy pin
from) — a mismatch is rejected with 400, before any catalog entry is even
resolved. See `imagingRoleMustMatchLegacyCatalogPinExactly` in the test file.

A consumer that ever observes the two disagreeing (for example, a future bug,
or a stale cached read) has found a data-integrity problem. It must not
average, prefer one, or silently pick either; it should treat that spacecraft
as unpublishable-until-corrected, exactly as the publish API already refuses
to write such a state.

### Known consequence: this freezes the imaging catalog version

`POST /api/missions` (`CatalogApi.java:56-78`) only ever calls
`store.create("mission", profile.spacecraftId(), profile)`, and
`StateStore.create` is a plain `INSERT ... VALUES(?,?,1,?)`
(`StateStore.java:91-99`). **There is no CAS/update route for
`MissionProfile` anywhere in the mission-definition service** — its
`(catalogId, catalogVersion)` pin is immutable for the life of a spacecraft
record.

Combined with the strict-equality precedence rule above, this means the
imaging catalog version can never be rolled forward through this API: binding
`IMAGING` to any catalog version other than the frozen legacy pin — even a
newer, independently valid, approved catalog entry for the exact same
activity — is unreachable, and no admin action in the current API can unblock
it. `imagingRoleCannotRotateToNewerApprovedVersionOfSameActivity` in the test
file pins this as current, intended behaviour rather than a latent surprise:
it seeds both `imaging-craft:1` (the legacy pin) and `imaging-craft:2` (a
second, independently valid, approved entry) and asserts that binding
`IMAGING` to version 2 is rejected exactly like binding it to an unrelated
activity id would be.

This is the right default — it genuinely prevents a divergent second source
of truth for imaging — but it is an explicit, open decision for a future
change, not implemented here:

- **(a)** add a CAS/update route for `MissionProfile` so the legacy pin can
  move forward (mirroring the `expectedVersion` pattern every other
  mission-definition API in this service already uses); or
- **(b)** relax the `IMAGING`-must-equal-legacy-pin rule to a weaker
  consistency rule (for example, requiring only that the two reference the
  same catalog *activity id*, allowing the version to differ).

Neither option is implemented, and `CatalogApi.java` is not edited by this
change. See the handoff (`.local/claude-delegation/sonnet-mission-catalogs.md`)
for this stated neutrally as a decision for Codex.

## API (`msc.services.missiondefinition.MissionCatalogBindingApi`)

Mirrors `AgilityApi` / `SimulationPlanningModelApi` exactly in shape and
validation style. All paths require authentication; internal paths
additionally require SERVICE at the filter-chain level
(`SecurityConfiguration`'s `/internal/**` matcher).

- `POST /api/mission-catalog-bindings`: `{expectedVersion, bindings}`,
  `hasRole('ADMIN')`, requires `Idempotency-Key`. First version expects 0.
  Inside one `store.idempotent` + `store.lock("mission-catalog-bindings:" +
  spacecraftId)` + `store.require("mission", ...)` transaction:
  1. `bindings.missionDefinitionVersion` must equal the stored mission's
     `missionDefinitionVersion` (400 otherwise).
  2. The `IMAGING` role's reference must equal the mission's legacy
     `(catalogId, catalogVersion)` pin exactly (400 otherwise — see
     "Precedence" above).
  3. Every role's `CatalogReference` is resolved via `store.require("catalog",
     catalogId + ":" + catalogVersion, CatalogEntry.class)` — the same lookup
     key shape `CatalogApi` uses. A missing catalog id or non-existent
     version is rejected (404).
  4. Each resolved entry's `template().operation()` must equal the role's
     required operation (400 otherwise — see "Role-to-operation mapping").
  5. A stale `expectedVersion` is rejected (409). Otherwise `store.create`/
     `store.update`, the immutable history write, and the
     `MissionCatalogBindingsPublished` outbox event happen in the same
     transaction.
- `GET /api/mission-catalog-bindings/{spacecraftId}` and
  `/internal/mission-catalog-bindings/{spacecraftId}`: current version,
  `hasAnyRole('ADMIN','OPERATOR','SERVICE')`.
- `GET /api/mission-catalog-bindings/{spacecraftId}/versions/{version}` and
  the equivalent `/internal/...` route: immutable exact version, same roles.

### A known-dead defensive check

`CatalogContracts.CatalogEntry`'s own compact constructor already throws
`IllegalArgumentException` whenever `!activity.approved()`, and that
constructor runs on every path that can produce a `CatalogEntry` in this
codebase, including Jackson record deserialization on read from the store. So
no unapproved `CatalogEntry` can ever reach `MissionCatalogBindingApi`, and
its own `!entry.activity().approved()` re-check is provably unreachable under
the current domain invariant. It is kept only as defense in depth in case
that invariant is ever loosened, or a record is read by a path that bypasses
the constructor. This means acceptance check B3's "an unapproved activity is
rejected" sub-case could not be exercised as a genuinely stored fixture; the
test file documents this explicitly next to `missingCatalogReferenceRejected`
and instead exercises the sub-case that is genuinely reachable: a role
referencing a missing catalog id or a non-existent version (404).

## What this does not do

- **This API is not wired into Planning in any way.** Planning currently
  reads only the single legacy pin via `PlanningInputs` at
  `/internal/catalog/{id}/versions/{version}`
  (`services/msc-planning-service/src/main/java/msc/services/planning/PlanningInputs.java:280-282`).
  No Planning code was read for the purpose of changing it and none was
  changed as part of this work.
- No `PlanCandidate` or `PlanningRun` is constructed or affected by this
  change.
- `EventTopology.java` is not edited by this change (see the integration
  request below).

## Integration request for Codex (not applied by this change)

1. **Routing, not automatic churn.** `EventTopology.ROUTES`
   (`msc-platform/src/main/java/msc/platform/EventTopology.java:10-54`) is an
   explicit per-service allow-list of event types, not a wildcard: the
   `"planning"` entry already names `MissionDefinitionPublished`,
   `ActivityDefinitionPublished`, `AgilityModelPublished`,
   `SimulationPlanningModelPublished`, and others by exact type string.
   `MissionCatalogBindingsPublished` is **not** in that list, so — unlike
   what an earlier draft of this plan assumed — publishing a binding today
   does **not** reach Planning's queue at all; it is simply not delivered.
   This is a request for Codex to consider adding
   `"MissionCatalogBindingsPublished"` to that `"planning"` route entry,
   alongside the search-budget and other event-routing work already in
   progress, not a warning about live behaviour that is already happening.
2. **If routed, `PlanningEvents.handle`'s dispatch still has no dedicated
   case for this event type** (mirroring the same open point already
   recorded for `SimulationPlanningModelPublished` in
   `docs/backend/simulation-planning-model.md`): it would fall into whatever
   `PlanningEvents.handle`'s `default` branch currently does. That is a
   second, separate decision from (1) and should be made together with it,
   not assumed.
3. **How Planning could consume these bindings, concretely, once it reads
   them (not requested to be done here):** `PlanningInputs` currently reads
   the legacy single pin via `GET
   /internal/catalog/{profile.catalogId()}/versions/{profile.catalogVersion()}`
   against `mission-definition`. A parallel read of `GET
   /internal/mission-catalog-bindings/{spacecraftId}` (SERVICE role, already
   implemented and tested by this change) would return the current
   `Bindings` for that spacecraft. Because of the precedence rule above, its
   `IMAGING` role reference is always identical to the legacy pin — so
   Planning does not need to reconcile two imaging definitions, only to read
   the additional `DOWNLINK` (and, if present, `MANEUVER`) references the
   legacy pin cannot express, then resolve each via the same
   `/internal/catalog/{id}/versions/{version}` lookup it already performs
   for imaging. This binding intentionally leaves that read, and any new
   `PlanningDataSnapshot.Input` category it might require, entirely to
   Codex; nothing here assumes what shape Planning's own consumption should
   take beyond the exact-version pin it should use.

Until (1) is applied, `MissionCatalogBindingsPublished` events are published
to the outbox/exchange but are not delivered to Planning's queue; the
bindings are durable, versioned, ADMIN-approved state, readable directly by
SERVICE/ADMIN/OPERATOR callers, but Planning does not yet see publication
events for them.
