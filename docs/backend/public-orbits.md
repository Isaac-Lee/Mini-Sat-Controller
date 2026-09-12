# Public NORAD orbit references and SPACEEYE-T1

Implemented on `codex/architecture-foundation-v0.1`; the broader backend remains in progress.
The user selected **SPACEEYE-T1, NORAD 63229** as the first target. Its canonical
public-orbit spacecraft key is `norad-63229`. No satellite ID is hardcoded in the
propagation path. The migration seeds a disabled catalog entry; enable it explicitly
in each environment. Collection was enabled in the current local development database.

## Data and numerical boundary

Reference Data queries the fixed CelesTrak GP endpoint with `CATNR` and `FORMAT=JSON`.
GP JSON carries the mean elements conventionally distributed as TLEs, while retaining
more precision and supporting catalog IDs through nine digits. This implementation
fetches GP JSON; it does **not** expose a two-line TLE upload/export endpoint.

Each immutable snapshot preserves the provider URL, original JSON, SHA-256,
fetch instant, NORAD identity and UTC element epoch. The latest pointer cannot move
to an older epoch. Repeated identical content reuses the snapshot and its original
fetch instant; the registry separately records the most recent successful fetch.
A successful fetch does not imply a recent element epoch. Read and display both.

Flight Dynamics explicitly imports a pinned snapshot through Reference Data's
internal HTTP API into its own database. Import does not designate an operational
orbit and does not grant commanding authority. Its SGP4/SDP4 adapter consumes the
mean elements directly using Orekit 13.1.8, with isolated UTC and TEME frames.
It transforms TEME position **and velocity** to EME2000 before using the existing
Earth-fixed ground-track adapter. GP derivatives are converted from encoded
rev/day powers (n-dot/2 and n-double-dot/6) to Orekit's rad/s powers. No conversion
to the existing two-body propagator occurs.

Predictions pin the public snapshot ID, model, EOP/leap archive digest and TAI
horizon. Ephemerides and ground tracks use existing content-addressed S3 objects.
Contact predictions reuse the continuous event-root implementation and can be
read through the existing access-prediction endpoint used by Ground Operations.

The seven-day maximum distance from element epoch is a defensive software limit,
**not an accuracy qualification**. EOP coverage, sample limits and the 24-hour
access-search limit also apply. Public GP supplies neither covariance nor current
attitude, battery, payload state, maneuver intent or proof of acquisition. These
remain separate simulator/operational inputs. Point-imaging geometry alone is not
full-AOI or sensor feasibility. Actual spacecraft commanding remains simulator-only.

## Collection behavior

A durable PostgreSQL claim and two-hour cooldown cover scheduled and manual refresh,
including multiple service replicas. The scheduler checks every 30 seconds and claims
one enabled target. Provider-wide lease and pause state prevent concurrent provider
calls and stop collection after a transport or non-200 HTTP error. No redirects or
automatic HTTP retries are used. Responses are capped at 64 KiB with a 25-second
total deadline. A malformed/no-data/identity-mismatched response pauses that target.
Errors preserve the previous valid snapshot and are visible in status APIs.

After investigating an error, ADMIN can resume the affected target and, for a
transport failure, the provider. Resume never bypasses the existing cooldown.
A process crash may leave the last attempt without a result; its durable cooldown
still prevents an immediate duplicate external request. Reading current snapshots
never contacts CelesTrak. Additional deployments sharing an outbound IP should use
one shared collector/cache rather than independent databases querying the same targets.

## API sequence

All endpoints require authentication. Internal paths require SERVICE. Registry
mutations and explicit refresh/resume require ADMIN. Prediction mutations accept
OPERATOR or SERVICE; imports accept ADMIN or SERVICE. Flight Dynamics POST mutations
require `Idempotency-Key`. Registry PUT, refresh and resume have naturally idempotent
configuration/claim semantics instead of the stored idempotency response contract.

Reference Data (local port 8105):

| Method | Path | Body/result |
| --- | --- | --- |
| GET | `/api/tracked-satellites` | Registry, enabled flag, next attempt, errors, last snapshot |
| PUT | `/api/tracked-satellites/63229` | `{"noradId":63229,"displayName":"SPACEEYE-T1","enabled":true}` |
| POST | `/api/tracked-satellites/63229/refresh` | Collect if due; 409 if paused/disabled/busy/cooling down; inspect `last_error` on result |
| GET | `/api/tracked-satellites/63229/orbit` | Latest stored snapshot, including its element epoch |
| GET | `/internal/orbit-references/{id}` | Immutable pinned snapshot |
| GET | `/api/orbit-provider` | ADMIN provider-wide pause status |
| POST | `/api/tracked-satellites/63229/resume` | Clear target error and enable; preserve cooldown |
| POST | `/api/orbit-provider/resume` | Clear provider pause after review |

Flight Dynamics (local port 8103):

| Method | Path | Body/result |
| --- | --- | --- |
| POST | `/internal/public-orbits/import` | `{"snapshotId":"gp-63229-<sha256>"}` |
| GET | `/internal/public-orbits/{id}` | Imported snapshot and original provenance |
| GET | `/internal/orbit-inputs/{id}` | Explicit `PUBLIC_GP` or `CARTESIAN` kind and pinned source; ambiguous IDs are rejected |
| GET | `/internal/reference-context` | Loaded EOP coverage, UTC time model and reference archive digest |
| POST | `/api/public-orbit-designations/{spacecraftId}` | ADMIN only: `{solutionId,expectedVersion,decisionReference}` selects an imported GP snapshot with spacecraft binding, age checks and compare-and-set |
| POST | `/internal/public-orbits/{id}/predictions` | `{"horizon":{"start":<TAI instant>,"end":<TAI instant>},"stepSeconds":60}` |
| POST | `/internal/public-orbits/{id}/access-predictions` | Existing `AccessPrediction.Query` without a separate solutionId |
| GET | `/internal/predictions/{predictionId}/samples` | Existing streamed ephemeris/ground-track document |
| GET | `/internal/access-predictions/{predictionId}` | Existing contact/imaging geometry result |

Equivalent `/api/public-orbits/...` endpoints exist. Convert UTC with
`POST /internal/time/utc-to-tai` before constructing TAI horizons. Future mission
configuration should map the `norad-<id>` key to independently approved sensor and
simulator definitions; this feature does not invent SPACEEYE-T1 instrument specifications.

## Verification

On 2026-09-12 KST, the full build passed with 94 tests. Re-running the local
public-orbit verifier against the stored 63229 snapshot passed all nine checks,
producing 90 ground-track samples and four geometric Daejeon contact windows.
This run used the existing snapshot with epoch `2026-09-11T03:28:43.405248 UTC`;
it did not perform an extra provider fetch. After restarting Flight Dynamics from
the new build, its unified input endpoint returned the same stored source as
`PUBLIC_GP`, and the reference-context endpoint returned the pinned archive digest
and actual EOP coverage. The separate numerical integration suite below is an
earlier verification, not included in this 94-test run.

At the public-orbit checkpoint, `./mvnw verify`: **69 tests passed**, including GP identity/format validation,
9-digit catalog IDs, bounded response handling, PostgreSQL concurrent claims,
shared cooldown, immutable source history, stale lease fencing, older-epoch
non-regression, and provider-wide failure pause. The recorded test source is the
CelesTrak 63229 response fetched on 2026-09-11, with element epoch
`2026-09-11T03:28:43.405248 UTC`; it is a fixed historical fixture, never a current-data fallback.

The explicit Orekit reference suite adds **7 passing tests**, including two GP
integration tests. These compare GP propagation with traditional TLE serialization/
parsing, exercise position transformation and ground access, and reject old inputs.
They are consistency/regression tests using Orekit, not independent validation of
Orekit against mission tracking truth.

```sh
./mvnw -pl msc-orbit-adapter -am \
  -Dtest=GeneralPerturbationsIT,OrekitReferenceFramesIT,OrekitAccessPredictorIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/verify-public-orbit.py --collect
```

Set the two `MSC_TEST_OREKIT_*` variables described in [local runtime](local-runtime.md).
`--collect` enables periodic collection for NORAD 63229. Omit it to verify against
already collected data without initiating a provider refresh. `--norad-id` selects
another target; target-specific contact counts will differ.

The actual local API/PG/S3 run passed source-hash verification, role denial,
pinned service-to-service import, idempotency conflicts, SGP4 ground track,
contact prediction, stale input rejection and refresh cooldown. Its snapshot is
`gp-63229-ad803a753878e5e378e1b8cb42b1d26c1658c1f2c5df16e2ab72fe7d472d4713`.
The 2026-09-11 run produced 90 samples over 90 minutes and four geometric
contacts over 24 hours at the illustrative Daejeon point 36.35N/127.38E, altitude
100 m and minimum elevation 5 degrees. This is not a real station reservation.
Both services were restarted: the same reference snapshot, pinned Flight Dynamics
copy, 90 stored S3 samples and refresh cooldown survived. Detailed ephemeral
evidence is in `.local/public-orbit-verification.json`.

Sources: [CelesTrak GP formats](https://celestrak.org/NORAD/documentation/gp-data-formats.php),
[CelesTrak usage policy](https://celestrak.org/usage-policy.php),
[Orekit TLE conversion implementation](https://orekit.org/site-orekit-latest/xref/org/orekit/propagation/analytical/tle/TLE.html).
