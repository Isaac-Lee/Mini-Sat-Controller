# Operation resource profiles

An explicit ADMIN-published, versioned, per-operation resource-evidence
document for a spacecraft, owned by Mission Definition and keyed by
`spacecraftId`. It is a **new, separate** contract and API from
`CatalogContracts.MissionProfile`/`CatalogApi` and from
`MissionCatalogBindingContracts`/`MissionCatalogBindingApi` — none of those,
nor `CatalogApiTest`/`MissionCatalogBindingApiTest`, is modified by this
change, and every existing read of those keeps working exactly as before.

## Why this exists

Planning's resource forecast (`PlanningResources.java`) currently accepts
only an `IMAGE`-operation catalog entry as pinned resource evidence: any
activity whose bound catalog entry declares a different operation short-
circuits to the issue code `OPERATION_RESOURCE_MODEL_REQUIRED`
(`PlanningResources.java:180-181`), and every constructed `Load` hard-codes
`downlinkedMbPerSecond = 0` (`PlanningResources.java:203`). This is
deliberate: the catalog (`CatalogContracts.ResourceProfile`) has no downlink
rate field at all, and `ResourceTimeline.Load` needs a *rate*
(`generatedMbPerSecond`/`downlinkedMbPerSecond`), not the *total*
(`generatedMegabytes`) the catalog publishes — so a downlink figure cannot be
guessed or derived, only explicitly supplied. This slice supplies exactly
that: an explicit, ADMIN-approved, per-catalog-entry resource evidence
record, scoped to what the accepted architecture actually needs, not a
general modeling framework.

## Contract (`msc.contracts.OperationResourceContracts`,
`msc-contracts/src/main/java/msc/contracts/OperationResourceContracts.java`)

- `Operation`: a bounded enum — `IMAGE`, `DOWNLINK`, `MANEUVER` — whose names
  are the exact `CatalogContracts.CommandTemplate.operation` strings a
  resolved catalog entry must declare. This is deliberately **not**
  `MissionCatalogBindingContracts.Role`: a role names a spacecraft-level slot
  a catalog reference is bound into, while `Operation` here names the
  template operation a single profile's resource numbers are evidence for.
  Conflating the two would let a role label silently redefine what operation
  a profile's numbers actually apply to.
- `catalog` reuses `MissionCatalogBindingContracts.CatalogReference`
  read-only rather than defining a second, identical, exact-version
  reference record.
- `ExpectedCatalogResources(powerWatts, generatedMegabytes,
  propellantKilograms)`: same shape and units as
  `CatalogContracts.ResourceProfile`, same nonnegative-finite constraint.
  **This is a tripwire, never a second source of truth.** Publish rejects
  the document unless every field equals the resolved catalog entry's
  `resources()` exactly (see "Publish rules" below). It exists only to catch
  a catalog entry that has been swapped out from underneath an
  already-drafted profile; the catalog remains the only source of resource
  numbers in this codebase.
- `OperationResourceProfile(operation, catalog, expected,
  downlinkMegabytesPerSecond)`:
  - `downlinkMegabytesPerSecond` is a **rate**, in megabytes per second,
    matching `ResourceTimeline.Load.downlinkedMbPerSecond`'s units exactly
    — not a total, since the catalog only ever publishes a total
    (`generatedMegabytes`). It is required, finite, **strictly greater than
    zero**, and bounded above by `OperationResourceProfile
    .MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND` (10,000 Mb/s) for `DOWNLINK`,
    and must be `null` for `IMAGE` and `MANEUVER`.
    - **Why strictly positive, not merely nonnegative:** a zero rate would
      silently produce a storage reservoir that never drains while the
      profile still reports as validated — exactly the failure mode this
      project must avoid.
    - **The 10,000 Mb/s upper bound is an implementation/SIMULATION
      validation bound in this project's existing bounded-parameter style,
      not a spacecraft or ground-station specification.** It must never be
      read as a real downlink throughput figure.
    - **A positive rate does not prove a ground contact exists.** Booking
      and pass availability remain a separate `GROUND_RESERVATION` gate,
      `NOT_EVALUATED` by this contract — see "What this does not do" below.
  - `expected.generatedMegabytes()` and `expected.propellantKilograms()` are
    carried through **unconstrained beyond `ExpectedCatalogResources`'s own
    nonnegative-finite check and the tripwire equality against the resolved
    catalog entry.** In particular:
    - A `DOWNLINK` entry with nonzero approved `generatedMegabytes` alongside
      its explicit drain is accepted, not rejected: `ResourceTimeline`
      already nets production and drain on one `Load`
      (`storageRate = Math.max(0, production) - Math.max(0, downlink)`,
      `ResourceTimeline.java:222`), so an activity that both produces
      housekeeping/overhead data and drains storage is a legitimate case,
      not a producer/drain conflict to forbid.
    - A zero-propellant `MANEUVER` entry is accepted, not rejected: nothing
      in this codebase defines `MANEUVER` as necessarily consuming
      propellant, and this resource-evidence contract does not narrow that
      operation's semantics to make it true. Unmodeled manoeuvre physics and
      attitude feasibility remain a separate gate, out of scope here.
- `Profiles(spacecraftId, missionDefinitionVersion, environment, profiles,
  approvalReference, provenance)`: **one document per `spacecraftId` holding
  the full list of profiles**, mirroring the accepted
  `MissionCatalogBindingContracts.Bindings` precedent — not a composite
  `(spacecraftId, catalogId, catalogVersion)` key. This keeps one CAS
  stream, one advisory lock and one version history per spacecraft, and lets
  publish check cross-profile consistency (duplicate catalog references) in
  one place.
  - `environment` must be exactly `"SIMULATION"`.
  - Duplicate catalog references (the same `(catalogId, catalogVersion)`
    bound by more than one profile) are rejected in the record constructor,
    structurally, without a persistence lookup.
  - At least one profile is required.
  - `missionDefinitionVersion` must match the spacecraft's stored
    `MissionProfile.missionDefinitionVersion`, checked by the API exactly as
    `AgilityApi` and `SimulationPlanningModelApi` check it.
- `Publish(expectedVersion, profiles)`.

Nothing in this contract or API is invented: every `(catalogId,
catalogVersion)` is an explicit administrator input, resolved at publish time
against the already-approved activity catalog, and every
`downlinkMegabytesPerSecond` is an explicit administrator-supplied rate,
never an inferred station throughput. No instrument, station throughput,
activity duration, or resource value is invented anywhere in this contract.
Nothing is derived from NORAD identity or public orbit elements: SPACEEYE-T1
/ NORAD 63229 is public GP orbit tracking only, never a hardware or
instrument source.

## What this is not

- **This is resource evidence only.** It selects no activity and
  establishes no precedence over `MissionCatalogBindingContracts` or the
  legacy single-catalog `MissionProfile` pin, both of which stay untouched.
  Publishing a profile does not make an activity approved, scheduled, or
  feasible.
- **A `DOWNLINK` profile does not imply a ground contact exists.** Booking
  and pass availability remain a separate `GROUND_RESERVATION` gate, `NOT_
  EVALUATED`. This is the main overstated-safety risk in this slice: a
  resource forecast showing accumulated storage being drained by a
  `DOWNLINK` activity must never be read as "the data will actually get
  down" — whether a ground station is actually in view, booked, and able to
  receive at that rate is not evaluated here or anywhere else in this
  codebase yet.
- No thermal or wheel-momentum content is expressed, and nothing here is or
  implies a physical-truth or feasibility claim.
- `ResourceTimeline` models a `Load`'s rates as **constant across an
  activity's whole window**; a real downlink rate varies with ground-station
  elevation over the contact. This contract does not attempt to model that
  variation.

## Publish rules (`msc.services.missiondefinition.OperationResourceApi`)

Mirrors `AgilityApi`/`SimulationPlanningModelApi` exactly in shape and
validation style. All paths require authentication; internal paths
additionally require SERVICE at the filter-chain level.

- `POST /api/operation-resource-profiles`: `{expectedVersion, profiles}`,
  `hasRole('ADMIN')`, requires `Idempotency-Key`. First version expects 0.
  Inside one `store.idempotent` + `store.lock("operation-resource-profiles:"
  + spacecraftId)` + `store.require("mission", ...)` transaction:
  1. `profiles.missionDefinitionVersion` must equal the stored mission's
     `missionDefinitionVersion` (400 otherwise).
  2. Every profile's `CatalogReference` is resolved via
     `store.require("catalog", catalogId + ":" + catalogVersion,
     CatalogEntry.class)` — the same lookup key shape `CatalogApi` uses,
     byte-identical to `CatalogApi.key` (`CatalogApi.java:19-21`). A missing
     catalog id or non-existent version is rejected (404).
  3. Each resolved entry's `template().operation()` must equal
     `profile.operation().name()` (400 otherwise) — **the central invariant
     of this slice**: a catalog entry is never accepted as evidence for an
     operation it does not actually declare.
  4. Each resolved entry's `resources()` must equal `profile.expected()`
     field by field (400 otherwise) — the tripwire described above.
  5. A stale `expectedVersion` is rejected (409). Otherwise `store.create`/
     `store.update`, the immutable history write, and the
     `OperationResourceProfilesPublished` outbox event happen in the same
     transaction.
- `GET /api/operation-resource-profiles/{spacecraftId}` and
  `/internal/operation-resource-profiles/{spacecraftId}`: current version,
  `hasAnyRole('ADMIN','OPERATOR','SERVICE')`.
- `GET /api/operation-resource-profiles/{spacecraftId}/versions/{version}`
  and the equivalent `/internal/...` route: immutable exact version, same
  roles.

### A known-dead defensive check

Exactly as in `MissionCatalogBindingApi`: `CatalogContracts.CatalogEntry`'s
own compact constructor already throws `IllegalArgumentException` whenever
`!activity.approved()`, and that constructor runs on every path that can
produce a `CatalogEntry`, including Jackson record deserialization on read.
So no unapproved `CatalogEntry` can ever reach `OperationResourceApi`'s own
`!entry.activity().approved()` re-check — it is provably unreachable under
the current domain invariant, kept only as defense in depth. No test
exercises it as a reachable failure; `OperationResourceApiTest` documents
this next to `missingCatalogReferenceRejected` instead.

## Integration request for Codex (not applied by this change)

`OperationResourceProfilesPublished` is an **owner-input publication**,
exactly like `AgilityModelPublished` and `SimulationPlanningModelPublished`
— both of which are already in `EventTopology.ROUTES`'s `"planning"` entry
(`msc-platform/src/main/java/msc/platform/EventTopology.java:9-51`). Unlike
`MissionCatalogBindingsPublished` (still unrouted as of this writing), this
event type is the same *kind* of thing Planning already listens for, so the
natural next step is:

1. **Route `OperationResourceProfilesPublished` into the `"planning"` entry**
   alongside `AgilityModelPublished`/`SimulationPlanningModelPublished`, so
   publishing a profile invalidates whatever dirty-epoch/re-examine
   mechanism Planning already runs for those owner-input events.
2. **The event is an invalidation signal, not the evidence itself.** What
   Planning actually pins as resource evidence must come from a
   **synchronous `/internal` read**, exactly like every other planning
   input is pinned today (`PlanningInputs` reading
   `/internal/catalog/{id}/versions/{version}`), keyed by the exact
   `(catalogId, catalogVersion)` the candidate activity is bound to, plus an
   evidence sha recorded alongside it (mirroring
   `RESOURCE_CATALOG_EVIDENCE_HASH_MISMATCH`'s existing pattern in
   `PlanningResources.java:168-171`). A concrete read shape:
   `GET /internal/operation-resource-profiles/{spacecraftId}`, then select
   the `OperationResourceProfile` whose `catalog` equals the activity's
   pinned catalog reference and whose `operation` equals the entry's
   `template().operation()`.
3. **Do not subscribe this to any Flight Dynamics *output* event** (an
   illumination or access-window result). That is a materially different
   case — subscribing to an output produced by evaluating a query creates a
   self-wakeup loop — and nothing about this slice is that. This slice is
   an owner-input publication only, the same shape as the two owner-input
   event types already routed to Planning.
4. **The two concrete lines this unblocks, unchanged by this slice:**
   `PlanningResources.java:180-181` (the `if
   (!"IMAGE".equals(profile.template().operation()))` short-circuit to
   `OPERATION_RESOURCE_MODEL_REQUIRED`) and the `Load` construction at
   `PlanningResources.java:197-204` that hard-codes `downlinkedMbPerSecond =
   0`. Both are Codex-owned and neither is touched by this change; consuming
   this contract to change them is left entirely to Codex, as is any new
   `PlanningDataSnapshot.Input` category that read might require.

Until (1) is applied, `OperationResourceProfilesPublished` events are
published to the outbox/exchange but are not delivered to Planning's queue.

## Test fixtures

`OperationResourceApiTest` (`services/msc-mission-definition-service/src/
test/java/msc/services/missiondefinition/OperationResourceApiTest.java`)
seeds catalog entries under per-test-random ids of the shape
`imaging-<craft>`, `downlink-<craft>`, `maneuver-<craft>`, `wrong-op-
<craft>`, always at `catalogVersion = 1` (a second version, `99`, is used
only to exercise "missing version" as 404). None of these ids or the
`sim-v1` mission-definition-version string are meant to be reused outside
this test file.
