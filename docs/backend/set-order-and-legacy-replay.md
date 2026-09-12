# Canonical `Set` order, and legacy idempotent-replay reconstruction

Scale-out correctness fix, not a refactor. Full investigation and the
verified proof this implements:
`.local/claude-delegation/opus-set-order-review.md` (Opus, read-only). Worker
report: `.local/claude-delegation/sonnet-set-order.md`.

## The bug

`java.util.Set.copyOf` returns `ImmutableCollections.SetN`, whose iteration
order is a per-JVM randomized salt — unstable across JVM instances even for
enum-valued sets, reproduced empirically on this project's JDK
(`opus-set-order-review.md` section 1). `Json.canonical`
(`msc-platform/src/main/java/msc/platform/Json.java:53-67`) sorts JSON
*object* member names but deliberately preserves *array* element order,
because ordered arrays (access windows, trajectory samples, ground tracks)
are real data whose order must not be silently reordered. A `Set` serializes
to a JSON array, so its per-JVM order leaked into two places:

- **Class A** — `CatalogApi.get`'s public catalog snapshot: two
  mission-definition replicas could return byte-different JSON for the
  identical stored catalog version, which further diverges Planning's
  `Evidence.sha256` (computed over whatever order a given pod emitted) and
  therefore its cache/search keys across replicas.
- **Class B** — `CatalogApi.create`'s idempotency check: a byte-identical
  retry landing on a different replica could compute a different fingerprint
  and be rejected `409 Idempotency key reused for different request` —
  breaking the exact guarantee idempotency exists to provide, precisely when
  there is more than one replica.

## Part 1 — canonical `Set` construction

Seven fields, across `msc-domain` and `msc-contracts`, now construct their
`Set` in a fixed, JVM-stable order instead of `Set.copyOf`'s randomized one.
Declared field types are unchanged (`Set<...>`), so no caller changes, and
`equals`/`hashCode` stay order-independent (still real `Set`s).

Two small helpers added to `msc-domain/src/main/java/msc/domain/shared/Checks.java`,
next to the existing `text`/`positive`:

- `orderedEnumSet(Class<E> type, Collection<E> values)` — `EnumSet`-backed,
  ordinal order, wrapped unmodifiable. Unlike `EnumSet.copyOf`, this accepts
  an **empty** collection (several of the seven fields are legitimately empty
  — an allowed `Decision`, a fully-cleared `SafetyLatch`).
- `orderedSet(Collection<T> values, Comparator<? super T> order)` (plus a
  natural-order `orderedSet(Collection<String>)` overload) — `TreeSet`-backed,
  wrapped unmodifiable.

Both reject a null collection or a null element, matching `Set.copyOf`'s
existing contract exactly.

| Field | File | Order |
|---|---|---|
| `ActivityDefinition.exclusiveResources` | `msc-domain/.../missiondefinition/ActivityDefinition.java` | `ResourceId::value` natural order |
| `ActivityDefinition.allowedPhases` | same | `MissionPhase` ordinal |
| `ActivityDefinition.allowedModes` | same | `String` natural order |
| `ScheduledActivity.exclusiveResources` | `msc-domain/.../planning/ScheduledActivity.java` | `ResourceId::value` natural order |
| `SafetyLatch.reasons` | `msc-domain/.../anomaly/SafetyLatch.java` | `SafetyPolicy.Reason` ordinal |
| `CommandReleasePolicy.Decision.reasons` | `msc-domain/.../spacecraftcontrol/CommandReleasePolicy.java` | `CommandReleasePolicy.Reason` ordinal |
| `CatalogContracts.ParameterRule.allowedValues` | `msc-contracts/.../CatalogContracts.java` | `String` natural order |

`ResourceTimeline.Result.modeledResources` already used this same
`EnumSet.noneOf` + `Collections.unmodifiableSet` shape (inline, not via this
helper) and was **not** touched by this change, per scope.

**One-time effect on deploy:** changing serialized order invalidates
fingerprints computed *before* this change. Planning's evidence `sha256` and
`PlanningGeometry` cache keys are fail-safe (a mismatch there just costs one
extra bounded search / re-derivation). The `idempotency` table is not
fail-safe on its own — see Part 2.

## Part 2 — opt-in legacy-replay reconstruction, `CatalogApi.create` only

Existing `idempotency` rows under scope `catalog:<actor>` hold fingerprints
computed **before** this change, over whatever order that JVM's `Set.copyOf`
produced. After Part 1, a legitimate byte-identical retry hitting a
*different* pod computes the (now-canonical) fingerprint, which will not
match that old row, and would get a hard `409` — a regression introduced by
the fix itself. Part 2 repairs exactly that, for exactly that one call site.

### Why the reconstruction is sound (verified, not assumed)

`CatalogApi.create` passes the deserialized `@RequestBody CatalogEntry entry`
as the idempotency request, and its action returns
`store.create("catalog", key(...), entry)` = `new State<>(id, 1, entry)` —
**the same `entry` instance**. `StateStore.idempotent` stores
`fingerprint(request)` and `tree(action.get())` — both serializing that one
object inside one JVM call. So the stored legacy fingerprint equals
`canonical(response.body)` **by construction**, where `response` is the
stored `State<CatalogEntry>` wrapper (`{id, version, body}`). The `response`
column is `jsonb`: it normalizes object key order (neutral — `canonical`
already sorts object members) and **preserves array order** (exactly what
the proof needs).

### The mechanism (`StateStore.legacyReplay`, called from `StateStore.replay`
### only when a caller opts in)

On a fingerprint mismatch, only for a caller that passed a non-null
`Function<JsonNode, Object> legacyBodyDecoder`:

1. Recompute `fingerprint(response.body)` and require it to equal the stored
   fingerprint. **This can fail on a perfectly legitimate legacy row** —
   `jsonb` stores numbers as `numeric`, so a `double` Jackson wrote in
   exponent form (e.g. `ParameterRule.maximum = 1.0E10`, which the record
   permits) reads back as integral `10000000000`, and `canonical`'s
   `node.toString()` renders that differently. A failure here proves
   **nothing** about the incoming request — it only means the rescue could
   not be attempted. It must fall through to the strict conflict, never to
   acceptance. This is stated explicitly in the code (`StateStore.java`,
   `legacyReplay` javadoc and the inline comment at step (a)).
2. Decode `response.body` via the caller-supplied decoder — for
   `CatalogApi.create`, `body -> json.convert(body, CatalogEntry.class)` —
   which re-materializes the request under the **current** (canonical)
   constructors.
3. Canonically fingerprint that reconstruction and compare against the
   incoming request's canonical fingerprint.
4. Equal → return the **original stored response verbatim** (not a
   re-canonicalized copy — the legacy array order in the stored row is
   preserved on output, since nothing is rewritten).
5. Different, or any exception at any step (decode failure, null
   reconstruction, anything) → the existing strict `409`, unchanged.

### Hard constraints honored

- **Explicit opt-in only.** `StateStore.replay`/`idempotent` gained an
  overload taking `Function<JsonNode, Object> legacyBodyDecoder`; every
  existing call site keeps calling the original overload (now delegating
  with `null`), so their behavior is byte-unchanged. `CatalogApi.create`
  passes a decoder; `CatalogApi.mission` (same class, `MissionProfile`
  request, no `Set` field) does not, and stays strict — there is no
  inference from the `scope` string anywhere (an actor name could otherwise
  collide with the `catalog:` prefix).
- **Nothing is written on the rescue path.** `legacyReplay` only reads; the
  caller (`StateStore.idempotent`) returns `completed.get()` as soon as
  `replay` yields a value, before ever calling `action.get()` or the
  `INSERT INTO idempotency ...` — the exact same short-circuit an ordinary
  (non-legacy) replay hit already takes. No `state_head`, `state_history`,
  `outbox`, or new/updated `idempotency` row.
- **The stored legacy fingerprint is never rewritten.** There is no write
  path in `legacyReplay` at all.
- **Conflict detection does not weaken.** A changed scalar, a changed `Set`
  membership, or a changed genuinely-ordered `List`'s order all still change
  the canonical fingerprint and still produce `409` (see test list below).
- Every other `StateStore.replay`/`idempotent` caller across every other
  service is untouched and passes no decoder.

### Open decision this does *not* solve

The `idempotency` table has **no TTL and no cleanup anywhere in the
codebase** — the only statement touching it besides the two inserts is the
`SELECT` in `StateStore.replay`/`legacyReplay`. Pre-fix rows persist
indefinitely, so this legacy branch is permanent until someone deliberately
retires it (e.g. once operationally confident no more pre-fix retries can
arrive). That is a decision for whoever owns rollout/ops, not resolved here.

## Tests

See `.local/claude-delegation/sonnet-set-order.md` for the full list, exact
commands, and real executed output. Summary: 22 domain-record order tests +
3 contract order tests + 11 `StateStore`/`Json` tests were compiled and run
directly against the production sources (36/36 passing, real JDK 21
execution, no Maven). A Spring Boot + Testcontainers integration test
(`CatalogApiLegacyReplayTest`) covering the end-to-end HTTP path — legacy
rescue with `state_history`/`outbox` counts unchanged, a corrupted-proof row
still `409`, changed scalar/membership still `409`, and the `mission:` scope
staying strict — was written but **not executed** by this worker (no Maven
permitted); it should run under the module's normal `mvn test`.
