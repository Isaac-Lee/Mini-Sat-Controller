# ADR-0012 — Orekit for Flight Dynamics and reference time/frame conversion

Status: Accepted by the user on 2026-09-11.

Use Java Orekit outside the domain for orbit propagation, Earth-fixed/inertial
frame transformations and UTC/TAI conversion. Pin the library and reference data
independently. Numerical output remains a prediction, not measured truth.

The initial adapter uses Orekit 13.1.8 and a Cartesian initial state in EME2000.
Its explicitly named two-body Keplerian model supports simulator integration;
it is not an operational accuracy claim or an orbit-determination solution.
Each computation owns its propagator. A reference snapshot owns its isolated
DataContext; no process-global mutable default is used.

Reference data originate from the official orekit-data repository. Downloads
must use an immutable commit and a verified archive SHA-256. Earth-fixed
calculations must reject dates outside the loaded EOP history, rather than
silently treating unavailable Earth orientation as zero. Every output identifies
the input orbit, numerical model, reference digest, frame and coverage.

Actual mission force models, accuracy budgets, covariance estimation, OD/EKF,
and instrument calibration still require mission-specific validation. The
library decision does not resolve these questions or command authority.

Sources: [Orekit](https://www.orekit.org/),
[official data](https://gitlab.orekit.org/orekit/orekit-data).

## Public GP extension — 2026-09-11

Following the user selection of SPACEEYE-T1/NORAD 63229, the same approved Orekit
library also supplies SGP4/SDP4 propagation of public CelesTrak GP mean elements.
GP JSON is used instead of a five-column NORAD field to permit multi-satellite
expansion and nine-digit catalog IDs. This is an additional infrastructure adapter,
not replacement of the explicit two-body simulator model. Public orbit snapshots
and operational designations remain separate. See [behavior and evidence](../../backend/public-orbits.md).
