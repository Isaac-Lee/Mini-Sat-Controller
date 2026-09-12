# Target illumination and spacecraft eclipse

Geometric sun-elevation and eclipse prediction only. Not AOI sensor coverage, attitude
feasibility, resource forecasting, booking, weather, or command authority. Not wired into
Planning's `ILLUMINATION` gate by this work; see "Binding request for Codex" below.

## Spacecraft eclipse now covers real public-GP solutions, not just Cartesian input

`POST /api/spacecraft-eclipse` originally resolved only `kind="orbit"` (a Cartesian
`Trajectory.InitialState`, two-body-propagated), so a persisted public-GP solution id (e.g. the
mission target SPACEEYE-T1 / NORAD 63229, tracked via `PublicOrbitApi`) failed closed with 404.
That has been extended to a **dual lookup**, mirroring `OrbitApi.input(id)` exactly: look up
`kind="orbit"` and `kind="public-orbit"`; **409** if both resolve (ambiguous identity, matching
`OrbitApi.create`'s own conflict check on write), **404** if neither. A GP solution is propagated
with the real SGP4/SDP4 `TLEPropagator` via `GeneralPerturbationsAdapter.eclipse(MeanElements,
TimeWindow)` — **never** converted into a two-body `InitialState` (that class's javadoc exists
specifically to forbid this; `GeneralPerturbationsAdapter`'s own class javadoc repeats the same
prohibition for exactly this reason).

Changed production files (all pre-existing, all owned by this work):

- `msc-orbit-adapter/src/main/java/msc/orbit/OrekitIlluminationPredictor.java` — the eclipse
  computation (±7-day epoch check, `checkHorizon`, `EclipseDetector` construction, negative-g
  window extraction, complement) was extracted into a package-private, propagator-agnostic
  overload `spacecraftEclipse(MissionInstant epoch, Propagator propagator, TimeWindow horizon)`.
  The pre-existing public `spacecraftEclipse(InitialState, TimeWindow)` is now a **thin
  delegation** to it (`return spacecraftEclipse(initial.epoch(), time.propagator(initial),
  horizon);`), so the Cartesian path's behaviour is unchanged — confirmed by re-running every
  pre-existing `OrekitIlluminationPredictorIT`/`OrekitAccessPredictorIT`/`GeneralPerturbationsIT`
  test unmodified against the new code (see "Verification performed").
- `msc-orbit-adapter/src/main/java/msc/orbit/GeneralPerturbationsAdapter.java` — added `public
  OrekitIlluminationPredictor.SpacecraftEclipse eclipse(MeanElements e, TimeWindow horizon)`,
  shaped exactly like the pre-existing `access(...)`: `new
  OrekitIlluminationPredictor(references).spacecraftEclipse(epoch(e), propagator(e), horizon)`,
  using the real TEME `TLEPropagator` from `propagator(MeanElements)`.
- `msc-contracts/src/main/java/msc/contracts/IlluminationContracts.java` —
  `SpacecraftEclipseResult` gained `Optional<String> orbitPropagationModel`, so a GP-derived
  result is distinguishable from a Cartesian one (see "Model binding" below).
- `services/msc-flight-dynamics-service/src/main/java/msc/services/flightdynamics/IlluminationApi.java`
  — `spacecraftEclipse` does the dual lookup above, derives `spacecraftId` as `"norad-" +
  elements.noradId()` for a GP source (the same owner invariant `PublicOrbitApi.designate`
  enforces at write time) and as `initial.spacecraftId()` for Cartesian, validates
  `snapshot.id().equals(request.solutionId())` for GP (the same source-consistency check
  `PublicOrbitApi.ingest` performs), and binds the exact propagation model used (see below).
  `store.replay(scope, key, request)` still runs before either lookup or any propagation, so
  idempotent replay continues to precede expensive compute.

## Model binding: `orbitPropagationModel`, and why empty is not a default

`SpacecraftEclipseResult.eclipseModel` names the **eclipse/shadow geometry model**
(`OrekitIlluminationPredictor.ECLIPSE_MODEL`) and is identical for every orbit kind — it says
nothing about which propagator produced the underlying trajectory. The new
`orbitPropagationModel` field closes that gap: every **new** write populates it with the exact
model that was actually used —
`msc.orbit.KeplerianOrbitAdapter.MODEL` (`"orekit-13.1.8-keplerian-two-body-WGS84-mu"`) for
Cartesian input, `msc.orbit.GeneralPerturbationsAdapter.MODEL`
(`"orekit-13.1.8-SGP4-SDP4-public-GP"`) for a public-GP source.

`Optional.empty()` means **legacy, unbound**: the result predates this field and its true source
model was never recorded. It must never be produced by a new write, and must never be read as
"GP" or as any other qualified default — it carries no information about which model was actually
used. Compatibility is handled entirely inside `SpacecraftEclipseResult` itself, with no separate
evidence envelope (judged unnecessary: a single `Optional` field plus a preserved constructor
fully satisfies "old JSON keeps reading, new writes are unambiguous," and an envelope would add a
wrapper type with no additional guarantee):

- The compact constructor normalizes a literal `null` to `Optional.empty()` and validates a
  *present* value with the same `Checks.text` every other identity field on this record uses (a
  present-but-blank model is rejected, exactly like a blank `solutionId` or `eclipseModel` would
  be).
- The **pre-existing ten-argument constructor is preserved unchanged** and now delegates to the
  eleven-argument canonical one with `Optional.empty()`, so any existing caller, and — more
  importantly — Jackson deserializing a **historical stored row that has no
  `orbitPropagationModel` JSON property at all**, keeps working exactly as before this field
  existed, reading back an empty Optional rather than throwing. This holds regardless of whether
  the local database currently has any `spacecraft-eclipse` rows; correctness does not depend on
  the table being empty (see "Accuracy scope" below for the same "carry forward, never silently
  upgrade" discipline applied to the accuracy language).
- Every result — Cartesian or GP — still carries the same `ECLIPSE_MODEL_ACCURACY_NOTE` verbatim.
  SGP4/SDP4 propagates the orbit more precisely than a two-body Keplerian model, but it does
  **nothing** to characterise the solar-position accuracy discussed below; a GP eclipse result
  must not read as more authoritative than a Cartesian one just because its orbit model is
  better-known.

## TEME risk — proved by test, not reasoned about

`TLEPropagator` propagates in TEME, not EME2000 (`GeneralPerturbationsAdapter.predict` already
carries its own explicit warning about this when transforming ephemeris samples).
`EclipseDetector` instead works directly from the propagated `SpacecraftState`'s own frame against
the `OneAxisEllipsoid` body frame, so Orekit is relied on to transform internally — exactly the
kind of boundary where a silent frame defect would hide. This was proved by test, not assumed:
`msc-orbit-adapter/src/test/java/msc/orbit/GeneralPerturbationsEclipseIT.java` builds its own
`TLEPropagator` + `EclipseDetector` (own tolerances, no object shared with production code beyond
the raw two-line TLE text and the pinned reference archive) and (G1) compares its independently
detected eclipse-window boundaries against `GeneralPerturbationsAdapter.eclipse`'s real output
within a 0.25 s tolerance (≈250–500× either detector's 0.5–1 ms root tolerance), and (G2) samples
`EclipseDetector.g()` directly, several seconds inside a reported eclipse window and inside a
reported sunlit window, on that independent instance, asserting the sign convention. Both pass
against the real SPACEEYE-T1 / NORAD 63229 fixture; see "Verification performed" for the actual
executed output.

## Two different questions

**Target illumination** answers: is a ground point lit enough to image? It is a sun-elevation
test at a fixed point, independent of any spacecraft trajectory, and is what
`SimulationPlanningContracts.Model.minimumSunElevationDegrees` is for.

**Spacecraft eclipse** answers: is the vehicle in Earth's shadow? It is a different geometric
question about a propagated orbit, and it is the artifact that lets a downstream consumer build
real per-interval sunlit/eclipse segments instead of relying on one orbit-averaged illumination
fraction, which cannot bound battery behaviour during eclipse (`Planning resource assessments`
already documents this: "Generation uses the explicit eclipse bound throughout; aggregate
sunlight fraction is never treated as constant power" — this work supplies the actual per-interval
eclipse/sunlit windows that a future tightening of that bound would consume).

## New files (original feature) and files changed for the GP extension

- `msc-orbit-adapter/src/main/java/msc/orbit/OrekitIlluminationPredictor.java` — computation
  engine, mirrors `OrekitAccessPredictor`'s explicit root-tolerance/max-check event-logging
  pattern. No dependency on `msc-contracts` (the orbit-adapter module does not depend on it);
  returns its own small `PointIllumination`/`SpacecraftEclipse` records. *Changed* for the GP
  extension: the eclipse computation is now propagator-agnostic (see above).
- `msc-orbit-adapter/src/main/java/msc/orbit/GeneralPerturbationsAdapter.java` — *changed*: added
  `eclipse(MeanElements, TimeWindow)`.
- `msc-contracts/src/main/java/msc/contracts/IlluminationContracts.java` — `Aoi`, `GroundPoint`,
  `TargetIlluminationQuery`, `SampledPointIllumination`, `AoiIlluminationScope`,
  `TargetIlluminationResult`, `SpacecraftEclipseResult`, and the `intersectAll` window-intersection
  utility. *Changed*: `SpacecraftEclipseResult` gained `Optional<String> orbitPropagationModel`
  (see "Model binding" above).
- `services/msc-flight-dynamics-service/src/main/java/msc/services/flightdynamics/IlluminationApi.java`
  — owner API: `POST/GET /api|/internal/target-illumination[/{id}]` and
  `POST/GET /api|/internal/spacecraft-eclipse[/{id}]`. *Changed*: `spacecraftEclipse` does the
  dual Cartesian/public-GP lookup and model binding described above; no new endpoints, no change
  to `target-illumination`.

Tests: `msc-contracts/src/test/java/msc/contracts/IlluminationContractsTest.java`,
`msc-orbit-adapter/src/test/java/msc/orbit/OrekitIlluminationPredictorIT.java`,
`msc-orbit-adapter/src/test/java/msc/orbit/GeneralPerturbationsEclipseIT.java` (new, GP-specific
numerical evidence),
`services/msc-flight-dynamics-service/src/test/java/msc/services/flightdynamics/IlluminationApiTest.java`
(structural, unchanged), `services/msc-flight-dynamics-service/src/test/java/msc/services/flightdynamics/SpacecraftEclipseApiIT.java`
(new, real DB/idempotency/model-binding evidence).

## Reference data: none added

The pinned archive at `.local/orekit/<commit>/time-frames.zip` contains exactly `tai-utc.dat` and
`Earth-Orientation-Parameters/IAU-2000/finals2000A.all` — no JPL/INPOP binary ephemeris. Verified
directly against the real ZIP entries (`java.util.zip.ZipFile`, JDK-only — see below), not by
parsing the companion `manifest.json`, which can drift from the archive's actual contents.
`org.orekit.bodies.AnalyticalSolarPositionProvider` was chosen specifically because it is
analytical and data-free (constructors `(DataContext)` and `()`, implementing
`ExtendedPositionProvider`), so it works against this archive unchanged.
`CelestialBodyFactory.getSun()` would fail here (it resolves through `JPLEphemeridesLoader`,
which needs a DE/INPOP file this archive does not have).
`OrekitIlluminationPredictorIT#pinnedArchiveIsUtcEopOnlyWithNoSolarEphemeris` pins this scope
assumption. It was rewritten in this work to assert the exact set of real ZIP entry names
(`java.util.Set.of("tai-utc.dat",
"Earth-Orientation-Parameters/IAU-2000/finals2000A.all")` against
`java.util.Set.copyOf(zipEntryNames)`, verified by directly listing the pinned archive's entries
before writing the assertion) plus the same filename-pattern guard against a future DE/ephemeris
file, instead of regex-parsing `manifest.json`'s `"scope"`/`"files"` fields. All other numeric
tests in that file are unchanged from before this work (no tolerance, threshold, or
window-expectation was edited).

## Accuracy scope — stated, not implied

`OrekitIlluminationPredictor.SOLAR_MODEL` = `"orekit-13.1.8-analytical-solar-position"`, carried
in every `TargetIlluminationResult`. `OrekitIlluminationPredictor.ECLIPSE_MODEL` =
`"orekit-13.1.8-finite-sun-penumbra-inclusive-eclipse-WGS84"`, carried in every
`SpacecraftEclipseResult`. Both results also carry an explicit accuracy-note string
(`SOLAR_MODEL_ACCURACY_NOTE` / `ECLIPSE_MODEL_ACCURACY_NOTE`):

- The analytical solar ephemeris is materially less accurate than a JPL/INPOP binary ephemeris.
  **No numeric accuracy bound is stated** because none is sourced in this build environment: no
  Orekit javadoc/source jar and no network access were available to confirm one. The note says
  exactly that and labels the model's error an **uncharacterised, assumed simulation margin**,
  never a mission-qualified bound. Do not treat any number derived from it as validated accuracy.
- Target illumination compares `minimumSunElevationDegrees` against the **true (geometric)** sun
  elevation. **No atmospheric refraction is modelled** (`GroundAtNightDetector` is constructed
  with a no-op `AtmosphericRefractionModel` returning `0.0`), so reported dawn/dusk crossings do
  not include the correction a real horizon observation would show.
- Spacecraft eclipse uses a finite-Sun, penumbra-inclusive spherical-shadow test
  (`EclipseDetector(sun, Constants.SUN_RADIUS, earth).withPenumbra()`) against the WGS84 Earth
  ellipsoid. Penumbra inclusion widens the eclipse interval relative to a strict-umbra-only model,
  which is the conservative direction for a power-supply consumer that must not assume full
  generation during partial shadow. Atmospheric refraction/extinction near Earth's limb is not
  modelled here either.

## Sign-convention evidence (the single highest-consequence assumption in this code)

Orekit's own javadoc/source was not available in this build environment (no sources jar, no
network access), so the sign of `GroundAtNightDetector.g()` and `EclipseDetector.g()` is **not**
sourced from Orekit documentation. It is established by two executed regression tests, run
directly against the pinned archive via the JUnit Platform Launcher **outside Maven** (see
"Verification performed" below for exact commands/output):

- `OrekitIlluminationPredictorIT#targetIlluminationWindowsAreOrderedNonOverlappingAndWithinHorizon`
  and `#targetIlluminationExcludesPointsBelowConfiguredElevation` together prove
  `GroundAtNightDetector.g() < 0` means illuminated: local solar noon at the equator/prime
  meridian falls inside a reported illuminated window at a lenient threshold, and falls outside
  one at a demanding 89° threshold. An inverted sign would fail both.
- `#spacecraftEclipseClassifiesKnownSunlitAndShadowedStartingGeometryCorrectly` proves
  `EclipseDetector.g() < 0` means eclipsed: a synthetic circular orbit placed exactly on the
  Sun-ward side of Earth (using the real Sun direction from `AnalyticalSolarPositionProvider` at
  that epoch) is classified fully sunlit with zero eclipse, and the same orbit placed exactly on
  the anti-Sun side is classified fully eclipsed with zero sunlit time. An inverted sign would
  swap these and fail both assertions.
- `#groundAtNightDetectorDependsOnTimeOnlyInvariantHolds` pins the invariant that justifies using
  a synthetic, non-physical clock orbit (`OrekitIlluminationPredictor.CLOCK_ONLY_ORBIT_RADIUS_METERS`)
  to drive time-stepping for target illumination: `GroundAtNightDetector.dependsOnTimeOnly()` must
  be `true`. `targetIllumination` also asserts this at runtime and throws if it is ever `false`,
  so a future Orekit upgrade that changed it would fail loudly instead of silently returning wrong
  windows.

## AOI sampling: five points, never a full-AOI claim

`GroundAtNightDetector` binds one point. `IlluminationContracts.Aoi.fivePointSample()` evaluates
the AOI centre plus its four corners; `IlluminationApi.targetIllumination` computes each point's
illuminated windows separately (`TargetIlluminationResult.points()`) and their intersection
(`sampledPointsIntersection()`), labelled `AoiIlluminationScope.SAMPLED_POINTS_ONLY`.

**This is the only scope label that exists.** There is deliberately no `FULL_AOI_PROVEN` value and
no `LOWER_BOUND` framing: **the intersection of five sampled points' illuminated windows is an
over-approximation of full-AOI illumination, not a lower bound.** The set of times when the whole
AOI is illuminated is a *subset* of the set of times when all five sampled points are
simultaneously illuminated — a dark patch between the samples is undetectable by this method. Any
consumer must treat full-AOI illumination as `NOT_EVALUATED`; this contract never claims otherwise.
`TargetIlluminationResult`'s constructor requires exactly five points, so a centre-only (or any
partial) sample cannot be represented as an AOI-level result at all —
`IlluminationContractsTest#centreOnlySamplingCanNeverConstructAnAoiLevelResult` and
`#aoiIlluminationScopeHasNoFullAoiValue` pin both of these structurally.

## Antimeridian and pole rejection

`IlluminationContracts.Aoi`'s compact constructor rejects `westLongitudeDegrees >=
eastLongitudeDegrees` (an antimeridian-crossing box expressed as west≥east is rejected explicitly,
never wrapped or mis-centred) and rejects any edge within `POLE_GUARD_DEGREES` (89°) of a pole,
since corner sampling and topocentric frames are degenerate there.

## Reuse and boundaries (E6)

`IlluminationApi` mirrors `OrbitApi.access`: `store.replay` before expensive computation,
`store.idempotent`/`store.create`/`store.event` to persist, `references.digest()` bound as
`referenceDigest`, `@PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")` and a required
`Idempotency-Key` header on both POST endpoints (checked structurally against `OrbitApi.access` by
reflection in `IlluminationApiTest`, so it cannot silently drift), and both `/api` and `/internal`
prefixes on every route. `OrekitIlluminationPredictor` enforces the same 24-hour-per-request
search limit and calls `references.requireCoverage` on both horizon ends, exactly as
`OrekitAccessPredictor` does, with an explicit root tolerance (`0.001` s) and max-check
(`60` s illumination / `10` s eclipse) recorded in every result.

No new orbit-adapter class was needed for the GP extension: `GeneralPerturbationsAdapter` already
lives in package `msc.orbit`, so it reaches `OrekitIlluminationPredictor`'s new package-private
overload, and both reach `OrekitReferenceFrames`'s package-private
`context()`/`earth()`/`requireCoverage()` accessors, without modifying `OrekitReferenceFrames`
itself (still untouched).

## Binding request for Codex (Planning)

This work ends at a tested, retrievable owner API. It does not wire anything into Planning.

- Result shape: `TargetIlluminationResult` and `SpacecraftEclipseResult`
  (`msc.contracts.IlluminationContracts`) are shaped like `AccessPrediction` — model id,
  `referenceDigest`, query/horizon, explicit tolerances, windows — so they can be pinned to the
  already-named `"ILLUMINATION"` gate in `PlanningOptions.java:58-66` directly, not reinterpreted.
- **Binding must be a synchronous owner-API read, never an event subscription.**
  `msc.platform.EventTopology.ROUTES` is an explicit per-service allow-list; nothing this service
  publishes (`TargetIlluminationPredicted`, `SpacecraftEclipsePredicted`) reaches Planning unless
  Codex adds it there, and it must not be added for this purpose — Planning subscribing to an
  illumination *output* event it triggered by its own query would be a self-wakeup loop (Planning
  wakes, runs a query, the query's own result event wakes Planning again). Instead, call
  `POST /internal/spacecraft-eclipse` (and, for target illumination against an AOI,
  `POST /internal/target-illumination`) synchronously with role `SERVICE` and an idempotency key
  derived from the request, the same way `OrbitApi.access` is already read synchronously.
- **Concrete consumer**: `docs/backend/planning-resources.md` states Planning's resource assessment
  currently uses "the explicit eclipse bound throughout" for generation, because "aggregate
  sunlight fraction is never treated as constant power" — a deliberately conservative but coarse
  choice, and that doc lists "illumination-dependent power qualification" under remaining
  integration. `SpacecraftEclipseResult.sunlitWindows()`/`.eclipseWindows()` are the real
  per-interval segments that would let `PlanningResources` (Codex-owned) build genuine
  piecewise `ResourceTimeline.Supply` entries — sunlit generation during `sunlitWindows`, eclipse
  generation during `eclipseWindows` — instead of one averaged or worst-case-throughout number,
  while remaining exactly as conservative during any interval this API could not evaluate.
- Target illumination is the direct source for `AOI_SENSOR_COVERAGE`-adjacent gates that need to
  know a ground point (or AOI) is lit; per the AOI-sampling section above, any consumer must keep
  treating full-AOI illumination as `NOT_EVALUATED` and only ever read
  `AoiIlluminationScope.SAMPLED_POINTS_ONLY` results as what they are.

## Verification performed (GP-eclipse extension, this session)

**No Maven build was run.** Root was packaging existing JARs for a live deployment and needed the
exact pre-built `target/classes`/jars untouched, so every check below was compiled and executed
directly with `javac`/`java` (JDK
`/private/tmp/msc-toolchain/jdk-21.0.12.1+1/Contents/Home`) against jars from
`/private/tmp/msc-toolchain/repository` and each dependency module's pre-existing `target/classes`
(read **only**; nothing was written under any module's `target/`). Class output went to this
session's scratchpad directory. This is real, executed evidence — including a real Testcontainers
Postgres via the Docker daemon already available in this environment — but it is not a substitute
for the real reactor build; root should run it once free:

```
mvn -pl msc-orbit-adapter,msc-contracts,services/msc-flight-dynamics-service -am test
```

**1. Regression: every pre-existing orbit-adapter test, unmodified test files, against the new
production code** (proves the Cartesian delegation is behaviour-preserving):

```
javac -d <scratch>/orbit-main -cp <msc-domain,msc-ports classes>:<orekit+hipparchus jars> \
  msc-orbit-adapter/src/main/java/msc/orbit/*.java
java ... RunTests msc.orbit.KeplerianOrbitAdapterTest msc.orbit.OrekitReferenceFramesIT \
  msc.orbit.OrekitAccessPredictorIT msc.orbit.OrekitIlluminationPredictorIT msc.orbit.GeneralPerturbationsIT
# -> 18 tests found, 18 successful, 0 failed
```

**2. All three production files compile cleanly** against real dependency jars/classes
(`msc-orbit-adapter` main sources; `msc-contracts` main sources; `IlluminationApi.java` alone
against jackson/spring-web/spring-security jars plus the two rebuilt modules above) — exit 0, no
errors, for each.

**3. `OrekitIlluminationPredictorIT` with the A1 fix, plus the new
`GeneralPerturbationsEclipseIT` (G1/G2/G4/TEME), plus the full pre-existing orbit-adapter suite,
together**:

```
javac ... OrekitIlluminationPredictorIT.java GeneralPerturbationsEclipseIT.java GeneralPerturbationsIT.java \
  OrekitAccessPredictorIT.java OrekitReferenceFramesIT.java KeplerianOrbitAdapterTest.java
MSC_TEST_OREKIT_ARCHIVE=.local/orekit/3e376b326373467647b1e246ebb083cd9e57cd68/time-frames.zip \
MSC_TEST_OREKIT_SHA256=ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1 \
java ... RunTests msc.orbit.OrekitIlluminationPredictorIT msc.orbit.GeneralPerturbationsEclipseIT \
  msc.orbit.GeneralPerturbationsIT msc.orbit.OrekitAccessPredictorIT msc.orbit.OrekitReferenceFramesIT \
  msc.orbit.KeplerianOrbitAdapterTest
# -> [        21 tests found           ]
# -> [        21 tests successful      ]
# -> [         0 tests failed          ]
```

The pinned archive's real ZIP entries were inspected directly (`unzip -l`) before writing the A1
assertion: exactly `Earth-Orientation-Parameters/IAU-2000/finals2000A.all` (3,763,572 bytes) and
`tai-utc.dat` (3,321 bytes) — matching what the new test now asserts as an exact `Set`.

**4. `IlluminationContractsTest`, including a new test for the compact-constructor
normalization/validation logic (`null`→empty, old constructor→empty, blank-when-present→reject,
populated value round-trips)**:

```
java ... RunTests msc.contracts.IlluminationContractsTest
# -> [        11 tests found           ]
# -> [        11 tests successful      ]
# -> [         0 tests failed          ]
```

**5. `SpacecraftEclipseApiIT` (new): real Testcontainers Postgres, real Flyway migration
(`msc-platform`'s `V1__service_owned_state.sql`), a real `StateStore`, and `IlluminationApi`
invoked directly** (no Spring context is booted, so `@PreAuthorize` is not enforced by this test —
see the note in the test's own javadoc and "Not run" below):

```
java ... RunTests msc.services.flightdynamics.SpacecraftEclipseApiIT
# -> [         5 tests found           ]
# -> [         5 tests successful      ]
# -> [         0 tests failed          ]
```

Covers, against a real database: Cartesian regression (persist, `GET` byte-identical via decoded
record equality, replay without a second row/event, conflicting-body-same-key → 409 `ApiException`,
unknown id → 404); the real public-GP path (SPACEEYE-T1 / NORAD 63229 fixture) persisting with
`orbitPropagationModel = GeneralPerturbationsAdapter.MODEL`, distinct from the Cartesian path's
`KeplerianOrbitAdapter.MODEL`, and the derived `spacecraftId = "norad-63229"`; a shared id between
`kind="orbit"` and `kind="public-orbit"` → `HttpStatus.CONFLICT`; an unknown id →
`HttpStatus.NOT_FOUND`; and legacy JSON (a hand-written string with no `orbitPropagationModel`
property at all) decoding through the real `ObjectMapper` construction pattern
(`JsonMapper.builder().findAndAddModules().build()`, the same one `PropellantApiTest` uses) to an
empty `Optional`, plus the same for an explicit `null` passed through the canonical constructor
via reflection.

**Every JSON/decoded-value comparison above compares typed records or explicit field values, never
raw `JsonNode` equality on numerics** (the risk root flagged from the Planning `LongNode`/`IntNode`
incident): `SpacecraftEclipseApiIT` decodes every response body to `SpacecraftEclipseResult` via
`Json.convert` before asserting on it, and compares `GET` results as decoded records
(`assertEquals(result, fetched)`), not as `JsonNode` trees.

**Not run, and not claimed:**

- **`@PreAuthorize` enforcement at the HTTP layer.** `SpacecraftEclipseApiIT` calls
  `IlluminationApi.spacecraftEclipse` directly (like the pre-existing `PropellantApiTest` calls
  `PropellantApi` directly), so Spring Security's method-interceptor AOP proxy is never in the
  loop — a call would succeed regardless of the caller's role in that test. Role parity with the
  existing, unchanged `OrbitApi.access` endpoint is asserted structurally (by reflection, executed,
  passing) in `IlluminationApiTest`, which this work did not modify; the annotation string on
  `spacecraftEclipse` is unchanged (`hasAnyRole('OPERATOR','SERVICE')`). A real `TestRestTemplate`
  + full Spring context test (like `CatalogApiTest` elsewhere in this codebase) would be needed to
  exercise enforcement itself, and was judged out of proportion to add for this bounded change.
- **`IlluminationApiTest` was not re-executed** in this session. It requires `OrbitApi.class` to
  compile, which pulls in `ObjectStorage`/AWS S3/`OrbitComputationPort` and the rest of that
  service's dependency graph — well beyond this change's four owned files. Verified by inspection
  instead: `spacecraftEclipse`'s method signature, `@PostMapping`/`@GetMapping` paths, and
  `@PreAuthorize` value are byte-identical to before this work; the test asserts exactly those
  properties.
- **The full multi-module Maven reactor.** Surefire configuration, dependency-version resolution
  consistency, and whether `msc-contracts`/`msc-orbit-adapter` compile cleanly under the real
  reactor (as opposed to this hand-assembled classpath) are not exercised by any of the above.
