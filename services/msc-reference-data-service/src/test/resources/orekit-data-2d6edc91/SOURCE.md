# Real Orekit reference-data fixture

Unmodified copies of official files, retrieved 2026-11-07 from
`https://gitlab.orekit.org/orekit/orekit-data/-/raw/2d6edc9118983d95c51303df05db16ada90b7ee2/<path>`
(commit "Updated to November 2026", 2026-11-06). Upstream origins per `update.sh`: USNO
`maia.usno.navy.mil/ser7/tai-utc.dat` and IERS rapid service `finals2000A.all`.

| path | git blob | sha256 (uncompressed) | bytes |
|---|---|---|---|
| tai-utc.dat | 89d16226f380b98240d9e47de4a845396d3c0ac0 | 3524e1ae34d67e858873a89e59983bbc5bd100221da898e796c1b36036a310c3 | 3321 |
| Earth-Orientation-Parameters/IAU-2000/finals2000A.all | 0433b30b099adb572277d6c36b4a2b99c0fa495c | 4c2308a3a782f561a84f549ab0e074e745aa950facd9dff56e2e60a7eac8f042 | 3776544 |

`finals2000A.all.gz` is `gzip -9 -n` of the raw file, only to keep the repository small;
tests decompress it and verify the git blob above. Tests never add leap seconds; the only
alteration they make is removing or corrupting real lines to prove rejection.
