# Public GP to traditional TLE export

Flight Dynamics now exposes TLE serialization of the preserved public GP elements. This
implementation is deployed in local Kubernetes (evidence below). It does not fetch a second provider response and
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


## Deployed verification — 2026-09-12

Flight Dynamics image `msc-flight-dynamics:193d434303b1b0429074f22f` rolled out,
preserving the existing Orekit volume and replica count. `verify-public-tle.py` passed
six check groups using the actual stored SPACEEYE-T1 GP snapshot
`gp-63229-4ae2cc8392a6d22b9f274f446ceed4a93d1aef38f3d0473ee0b8788c186ceae9`,
epoch `2026-09-11T16:05:32.652384` UTC. It verified the source hash, NORAD identity,
69-character line lengths, independently computed checksums, exact two-line download,
authenticated requester access, provenance headers, range rejection and unchanged raw GP.

Local API URLs are `http://localhost:8103/api/tracked-satellites/63229/tle` and the
`.txt` variant. They require authentication. The verifier saves the downloaded text to
`.local/spaceeye-t1-derived.tle`; evidence is `.local/public-tle-verification.json`
and `/private/tmp/msc-tle-live.log`. Export uses the latest collected snapshot, which is
not a promise of real-time orbital accuracy or a fresh provider fetch on every request.
