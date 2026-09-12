# Simulation planning model

An explicit ADMIN-published, versioned simulation planning model, owned by
Mission Definition and bound to a stored `MissionProfile`. It supplies the
mission phase/mode and simulation-only sensor/power assumptions that later
full feasibility evaluation needs and that `MissionProfile` does not already
carry. It is not itself a feasibility evaluator, and **sensor feasibility is
not integrated into Planning by this model** — see "What this does not do"
below.

## Why this exists

`ActivityDefinition.requireSchedulable(MissionPhase phase, String mode)`
(`msc-domain/src/main/java/msc/domain/missiondefinition/ActivityDefinition.java:32`)
is what `PlanCandidate.propose` calls to check an activity is approved for the
current phase/mode. Before this model, nothing published an explicit mission
phase or mode as simulation input, so that check was unreachable. Mission
phase/mode is the highest-value field this model adds.

`CatalogContracts.MissionProfile` already carries `batteryCapacityWh`,
`minimumBatteryWh`, `storageCapacityMb`, `propellantKg` and `rechargeWatts`.
This model does not restate any of them. It adds:

- `phase` (`msc.domain.anomaly.MissionPhase`: LEOP/COMMISSIONING/ROUTINE/
  CONTINGENCY/EOL) and `mode` (the string `ActivityDefinition.requireSchedulable`
  consumes).
- A bounded sensor coverage model: `swathWidthMeters`, `groundSampleDistanceMeters`,
  `maximumOffNadirDegrees` (>0..60), `minimumSunElevationDegrees` (0..<90), and
  `requiresWeatherEvaluation`, which the constructor requires to be `true` —
  there is no implicit clear-sky path. `swathWidthMeters` and
  `groundSampleDistanceMeters` carry generic sanity bounds (<=1,000,000 m and
  <=10,000 m respectively) to reject overflow/garbage input; they are not
  derived from, or intended to represent, any specific instrument's real
  specification.
- Conservative power/bus assumptions that map onto
  `msc.domain.planning.ResourceTimeline.Supply` and `.Limits`: `busDrawWatts`,
  `sunlitGenerationWatts`, `eclipseGenerationWatts` (constructor-enforced
  `eclipseGenerationWatts <= sunlitGenerationWatts`), `worstCaseSunlitFraction`
  in `[0,1]`, and `minimumPropellantKg` — the one `Limits` field
  `MissionProfile` does not supply. `minimumPropellantKg` is additionally
  rejected by the publish API (not the record constructor, which has no
  access to the mission) when it exceeds the bound mission's
  `MissionProfile.propellantKg` — a reserve larger than the spacecraft's
  entire propellant load is never a valid conservative assumption. No solar
  geometry, eclipse propagation, or other numerical model lives in
  mission-definition; these are explicit, bounded, ADMIN-approved simulation
  assumptions only.

  `worstCaseSunlitFraction` is an aggregate illumination assumption without
  a validity interval or eclipse ordering. It must **not** be converted to a
  constant weighted-average supply for battery feasibility: an eclipse early
  in the horizon can exhaust the battery even when average energy balances.
  `Model.conservativeSupply(horizon, exactModelVersionReference)` therefore
  uses `eclipseGenerationWatts` throughout the horizon with `busDrawWatts`.
  This is the lower of the two approved simulation generation bounds. A less
  pessimistic supply requires a pinned time-resolved illumination forecast;
  that integration is still pending. The aggregate fraction is retained for
  stored-model compatibility and is not used by this supply conversion.
- `environment`, which must equal `"SIMULATION"` exactly; `missionDefinitionVersion`,
  bound to the stored `MissionProfile.missionDefinitionVersion`; and a text
  `approvalReference`. No SPACEEYE-T1 hardware value is invented anywhere in
  this model — SPACEEYE-T1 / NORAD 63229 is public GP orbit tracking only,
  never a hardware telemetry or instrument source.

Cross-checking the sensor `maximumOffNadirDegrees` bound against the agility
model's `maximumOffNadirDegrees` belongs in Planning, not here, and is **not**
performed by this model or its API — it is a required Planning-side gate that
remains to be implemented there.

## API

Mirrors `AgilityApi` exactly in shape and validation style
(`services/msc-mission-definition-service/src/main/java/msc/services/missiondefinition/AgilityApi.java`).
All paths require authentication; internal paths additionally require
SERVICE at the filter-chain level.

- `POST /api/simulation-planning-models`: `{expectedVersion, model}`,
  `hasRole('ADMIN')`, requires `Idempotency-Key`. First version expects 0.
  Publication is rejected (400) when `model.missionDefinitionVersion` does not
  equal the stored mission's `missionDefinitionVersion`, or when
  `model.minimumPropellantKg()` exceeds the stored mission's
  `MissionProfile.propellantKg()`; a stale `expectedVersion` is rejected
  (409). Publish, the compare-and-set, the
  immutable history write and the `SimulationPlanningModelPublished` outbox
  event happen inside one `store.idempotent` + `store.lock` +
  `store.require("mission", ...)` transaction, exactly like `AgilityApi`.
- `GET /api/simulation-planning-models/{spacecraftId}` and
  `/internal/simulation-planning-models/{spacecraftId}`: current version,
  `hasAnyRole('ADMIN','OPERATOR','SERVICE')`.
- `GET /api/simulation-planning-models/{spacecraftId}/versions/{version}` and
  the equivalent `/internal/...` route: immutable exact version, same roles.

## What this does not do

- **Sensor feasibility is not integrated into Planning.** This model only
  publishes bounded simulation assumptions. No Planning code was read or
  changed as part of this work, and nothing in this change makes Planning
  read this model, evaluate AOI/sensor coverage against it, or cross-check
  `maximumOffNadirDegrees` against the agility model — that cross-check
  remains an unimplemented Planning-side gate. What Planning's own gate
  states are, concretely, is Planning's own code to state; this document does
  not assert them, since Planning is under concurrent, independent revision
  and any specific claim here could go stale before integration.
- No power forecasting, eclipse propagation or solar geometry is performed
  anywhere in mission-definition; the power/bus fields here are static,
  conservative, ADMIN-approved bounds only.
- No `PlanCandidate` or `PlanningRun` is constructed by this change.

## Integration notes for Codex (not applied by this change)

1. **`PlanningEvents.handle` routes every unrecognised event type to
   `default -> intake.changedInputs()`**
   (`services/msc-planning-service/src/main/java/msc/services/planning/PlanningEvents.java:33`).
   `SimulationPlanningModelPublished` is a new, currently-unrecognised event
   type, so publishing this model will fall into that `default` branch and
   call `intake.changedInputs()`, bumping `planning_inputs_epoch` and waking
   queued planning work — the same way `AgilityModelPublished` already does.
   This is very likely desirable (planning should react to a newly published
   simulation planning model), but it is a live behaviour change with no
   dedicated handling, and it adds wakeup churn that interacts with the
   search-stage's per-revision search budget being built alongside this
   change. It should be reviewed together with that work, not treated as free.
2. **`msc.domain.planning.PlanningDataSnapshot.Input` has no `SENSOR` or
   `MISSION_PHASE` constant**, and `PlanningDataSnapshot`'s record constructor
   rejects any reference-map key set that is not the complete
   `EnumSet.allOf(Input.class)`. Adding either constant is a breaking change
   to every existing snapshot construction path and touches shared domain
   code neither Sonnet A nor Sonnet B owns. This model intentionally does not
   attempt that change; it is Codex/shared-domain work, tracked as D6 in the
   coordinator's review (`.local/claude-delegation/opus-plan.md`).

Until both of the above are addressed, this model is published and readable,
and mission phase/mode exist as durable, versioned, ADMIN-approved state — but
Planning cannot yet consume them to unblock `PlanCandidate.propose`.
