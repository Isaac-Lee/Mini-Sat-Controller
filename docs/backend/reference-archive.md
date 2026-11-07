# Reference archive collection and freshness — 2026-11-07

Reference-data now collects the Orekit UTC/EOP archive itself. Before this, the only source was
the pinned bootstrap `scripts/fetch-orekit-reference.py`, which stays in place for first-time setup.
All data is real. Nothing in production or test code makes up leap seconds or EOP values.

## Source and validation

- Source: the official `orekit-data` repository (the same one the bootstrap script pins), branch
  `main`. It is updated about monthly from USNO `tai-utc.dat` and the IERS rapid service
  `finals2000A.all`.
- Each run resolves the branch head to an immutable commit. It then reads GitLab's
  `X-Gitlab-Blob-Id` / `X-Gitlab-Content-Sha256` for each file at that commit and downloads the raw
  file. Each file is capped at 10 MB, requests have fixed URLs, and nothing is redirected or retried.
- Integrity: the git blob SHA-1 (the same check the bootstrap script runs) and the SHA-256 must
  both match the provider's values.
- Format: every `tai-utc.dat` line must match the USNO layout. Dates must increase, and offsets
  after 1972 must be whole seconds that change by exactly one second per step. Every
  `finals2000A.all` row must have a date that agrees with its MJD. Days must be consecutive. UT1
  flags must be `I` (measured) followed by `P` (predicted), with no gap before the trailing
  date-only rows.
- Cross-check: every UT1−UTC jump larger than 0.5 s must fall on a `tai-utc.dat` leap second, and
  every leap second inside the UT1 coverage must show such a jump.
- Consumer check: the deterministic archive (stored, sorted entries, 1980 timestamps) is loaded with
  `OrekitReferenceFrames`, the same class flight-dynamics uses.
- Coverage: the snapshot is rejected if the collection time is outside `[EOP start, expiresAt)`.

Any failure deletes the temporary `.part` file, so nothing reaches
`<archive-dir>/<commit>/time-frames.zip`, and the current snapshot is left as it was. Transport
failures (`IOException`) are retried after one hour. Invalid content pauses collection until an
ADMIN calls `POST /api/reference-archive/resume`. Collection is off until that same call is made
the first time.

## Snapshot and events

The `reference-snapshot/orekit-data-<commit>` state is immutable. It holds a
`ReferenceDataSnapshot`/`SnapshotRef` (provenance `orekit-data@<commit>`, validity
`[EOP start, expiresAt)`), the archive path and SHA-256, and each file's URL, git blob, SHA-256 and
size. All instants are TAI. `ReferenceSnapshotPublished` is published once per new commit. A new
snapshot becomes current only if its latest measured EOP is not older than the current one.

## Freshness

| Quantity | Definition |
|---|---|
| `latestMeasured` | Last `finals2000A.all` day whose UT1 flag is `I` |
| `eopEnd` | Last day with a UT1 value (`I` or `P`) |
| `expiresAt` | `min(eopEnd, latestMeasured + 150 d)` |

- **EXPIRED**: `now >= expiresAt`. UTC/UT1 conversions for "now" are no longer supported by the data.
- **STALE**: not expired, and either `now − latestMeasured > 45 d` or `expiresAt − now ≤ 30 d`.
- **FRESH**: everything else.

Why these numbers:

- **150 d** is the leap-second horizon. `tai-utc.dat` has no expiry date, and IERS Bulletin C
  announces leap seconds about six months ahead, in January for June 30 and in July for
  December 31. Data current at `latestMeasured` therefore cannot miss a leap second within roughly
  5½ months. 150 d is a conservative MSC policy, not an IERS standard.
- **45 d** covers orekit-data's roughly monthly cadence plus two weeks of slack, so one missed
  update shows as STALE.
- **30 d** is the warning period before expiry.

`GET /api/reference-snapshots/current` (also under `/internal/`) returns the snapshot, its freshness,
a `warning` string when expiry is near or data is stale, and the collector state. The scheduler logs
WARN while a warning is active. It publishes `ReferenceDataStale` once each time the state moves into
STALE or EXPIRED.

For the real 2026-11-06 commit, `latestMeasured` is 2026-11-05, `eopEnd` is 2027-11-12 and
`expiresAt` is 2027-04-04.

## Flight-dynamics pickup

Flight-dynamics behaviour is unchanged. At startup it still verifies `MSC_OREKIT_ARCHIVE` against
`MSC_OREKIT_SHA256` and mounts the volume read-only. Picking up a new snapshot is a deliberate,
reviewable operator step:

1. Read `current.archivePath` and `current.archiveSha256` from the status API. In Kubernetes the
   path is `/orekit/snapshots/<commit>/time-frames.zip`, which is the same path on both pods.
2. Set `MSC_OREKIT_ARCHIVE` and `MSC_OREKIT_SHA256` in the `msc-runtime` ConfigMap to those values.
3. Roll out flight-dynamics. A digest mismatch fails startup, so a wrong pair cannot load silently.

The new archive's bytes differ from the bootstrap script's zip (Java and Python zip headers
differ), so its digest is new even for the same commit.

## Kubernetes

`scripts/render-k8s.py` mounts the `orekit` volume's `snapshots` subPath read-write at
`/orekit/snapshots`, for reference-data only, and sets `MSC_REFERENCE_ARCHIVE_DIR`. The collector
is an in-process `@Scheduled` task: it runs every 5 minutes, takes a 6-hour cooldown with a DB
lease, and is replica-safe. There is no separate CronJob. Two pods share the volume, so the PVC
needs ReadWriteMany, or both pods must be on one node (local kind uses hostPath).

Resource estimate: no change to the 256Mi/250m request or the 512Mi/2 CPU limit. A new commit
costs about 3.8 MB of downloaded bytes held twice (raw files and zip), plus one Orekit EOP load
(about 20 000 entries) that is released after validation. Measurement still to do: record JVM
heap and CPU around one real collection in the cluster and compare them with this estimate.

## Known gap (not changed here)

For real files, Orekit's EOP history runs to the last date-only row (2028-01-01 for the
2026-11-06 commit). Past `eopEnd` it returns about 0 s for UT1−UTC: it gave 9.4e-6 s on
2027-11-20, against −0.229 s on 2027-11-12. `OrekitReferenceFrames.requireCoverage` accepts
those dates. This collector uses the real UT1 coverage, but flight-dynamics needs a separate fix.
