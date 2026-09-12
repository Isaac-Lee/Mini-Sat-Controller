# Owner-pinned solar interval evaluation

Flight Dynamics exposes `POST /api/solar-intervals` and `/internal/solar-intervals` to
OPERATOR/SERVICE, using the existing FD role convention. Input contains spacecraft identity,
an exact positive `assumptionsVersion`, and the existing target illumination query. The caller
cannot supply rate/error values in place of the owner's published assumptions.

FD retrieves that exact Mission Definition revision, checks envelope/body identity and
version, then enforces the current solar model, reference archive digest, geographic extent,
altitude and time coverage. Only then does it run the conditional interval adapter. Missing
owner assumptions produce 404; mismatched versions or out-of-scope queries fail before writing
any result. The underlying adapter enforces the 24-hour and 4096-sample limits.

Results retain the original request, full versioned owner assumptions with canonical SHA-256,
the solar model accuracy note and per-cell interval computation. The outcome is
SUPPORTED_BY_DECLARED_ASSUMPTIONS when the conservative lower elevation meets the requested
threshold, otherwise NOT_ESTABLISHED. NOT_ESTABLISHED is not proof of darkness, and the positive
outcome is not a hardware accuracy qualification. The threshold conversion is rounded upward.

Result/history, idempotency response and SolarIntervalEvaluated outbox event are stored together.
Same-key retries return the original result before any owner lookup. `GET /api/solar-intervals/{id}`
and its internal equivalent read persisted results under the existing FD security boundary.
The old point/rectangular event-search APIs are unchanged.

The integration test uses real PostgreSQL and pinned Orekit data, mocking only the Mission
Definition HTTP source. It checks positive conditional outcome, source/hash persistence,
replay without another owner call and rejection of an unexpected owner revision without a
new result. Planning consumption remains pending.

The focused run passed 16 tests across four classes with zero failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-solar-interval-api.log`).

Mission Definition and Flight Dynamics were independently deployed in the local kind cluster.
All ten Deployments remained ready, with Planning retaining two replicas. The real
`scripts/verify-solar-intervals.py` flow passed eight checks: publication/compute role boundaries,
exact source retention, conditional positive calculation and persisted read, replay after an
owner update, NOT_ESTABLISHED with a larger declared error, missing-version rejection and
out-of-extent rejection. Evidence is `.local/solar-interval-verification.json`. Its numeric
assumptions belong to an isolated synthetic mission and are not physical qualification.
