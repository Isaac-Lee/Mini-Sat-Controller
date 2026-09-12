# Simulation weather references

Reference Data owns immutable uniform cloud-field snapshots in PostgreSQL. ADMIN
publishes explicit synthetic inputs; this is not a live meteorological-provider
integration and does not infer real cloud conditions. The `SIMULATION` environment
is mandatory. `cloudFraction` is uniform throughout the supplied WGS84 rectangle
and validity window, so a contained AOI has the same modeled fraction. It is not a
regional average that can be extrapolated to an arbitrary subset.

## API

- `POST /api/weather/simulation-forecasts`: ADMIN, `Idempotency-Key`; body
  `{id, environment, issuedAt, validFor, area, cloudFraction, provenance}`.
  `id` is a UUID; issue/validity timestamps use explicit TAI. Future issue times,
  invalid bounds, nonfinite/out-of-range fractions and other environments are rejected.
- `GET /api/weather/forecasts/{id}` and `/internal/weather/forecasts/{id}`:
  exact immutable snapshot in a versioned state wrapper.
- `POST /api/weather/query` and `/internal/weather/query`: body `{area,horizon}`;
  selects the newest issued source covering the **whole** rectangle and interval,
  with deterministic ID ordering for equal issue times. No match returns 404.
  This query is read-only and does not require an idempotency key.

Reads permit ADMIN/OPERATOR/SERVICE; internal routes additionally require SERVICE.
A newer forecast covering only part of the requested interval does not conceal an
older forecast that covers it fully. Selection records no claim that either
forecast is an observation. Issue time, validity and provenance remain available.

Snapshot, searchable coverage row and `WeatherForecastPublished` outbox fact commit
atomically. PostgreSQL compares seconds/nanoseconds as pairs; a one-nanosecond
coverage gap is not rounded away. The event wakes waiting Planning work.

## Planning integration

Planning queries once per input collection for the request AOI and a horizon from
collection time to 24 hours later, shortened by a nearer request deadline. It
retains the query, exact selected snapshot and canonical JSON fingerprint in every
asset's WEATHER input. It verifies source ID, coverage and issue time independently.
A missing covering forecast remains an explicit missing input. A fraction above
the user's threshold produces `CLOUD_CRITERION_NOT_MET`. A simulation weather or
agility input combined with a HARDWARE telemetry binding produces
`SIMULATION_INPUTS_IN_HARDWARE_CONTEXT`.

This is an input gate, not a complete scheduling algorithm. More general weather
providers, spatial/time grids and forecast uncertainty require explicit models;
no silent fallback to clear skies exists.

## Verified on 2026-09-12

The full default suite passed 98 tests. Three new PostgreSQL-backed tests cover
source selection/history, idempotency, entire AOI/interval coverage, nanosecond
boundaries, future issue rejection, invalid fractions/environments, a newer partial
forecast and transaction rollback of snapshot/index/outbox.

`python3 scripts/verify-planning-inputs.py` passed eleven checks against separately
running local services, including the ADMIN write boundary, exact weather selection,
whole-horizon rejection, stored Planning evidence, cloud-limit diagnostic and
retention of the old source after a new cloud field is published. Its synthetic
fixture and result IDs are recorded in `.local/planning-input-verification.json`.
All existing public-orbit records are retained by additive migration V4.
