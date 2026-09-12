# Public GP to traditional TLE export

Flight Dynamics now exposes TLE serialization of the preserved public GP elements. This
source checkpoint is not deployed yet. It does not fetch a second provider response and
does not bypass Reference Data's provider cooldown or collection pause.

- GET `/api/tracked-satellites/{noradId}/tle` reads the latest collected GP snapshot from
  Reference Data and returns two lines plus NORAD/name, source snapshot/hash/URL, fetched
  time, original epoch and an explicit derived/rounded/non-telemetry representation label.
- GET `/api/tracked-satellites/{noradId}/tle.txt` returns exactly two newline-terminated
  lines, with `X-MSC-Orbit-Source: PUBLIC_GP_DERIVED_TLE` and the source snapshot ID.
- GET `/api/public-orbits/{snapshotId}/tle` serializes the exact GP snapshot already
  imported into Flight Dynamics, including historical snapshots.

ADMIN, OPERATOR, REQUESTER and SERVICE can read these endpoints. NORAD 63229 (SPACEEYE-T1)
is supported. Other tracked satellites within traditional TLE field limits work through
the same API; no spacecraft hardware values are inferred from identity.

The export uses the existing Orekit GP-to-TLE adapter with the same explicit UTC data
context. It requires 69-character lines and valid format/checksums, reparses them and checks
NORAD identity. Five-digit NORAD IDs, representable element/revolution numbers and epochs
1957..2056 are required; values outside these limits are rejected without truncation.
The underlying GP propagation continues to use its higher precision rather than replacing
stored elements with rounded TLE text. Export does not designate an operational orbit or
assert freshness; source epoch/fetched time remain available to the caller.

Tests use real Orekit reference data. They cover 63229 line lengths/checksums/reparse,
rejection of oversized IDs, exact source provenance and two-line text, wrong NORAD binding
and raw-source hash corruption. Existing SGP4/SDP4 propagation regressions run alongside.

Provider-original TLE collection and caller-supplied TLE import are still separate unfinished
features. This endpoint exports a derived TLE from the already collected GP JSON.
