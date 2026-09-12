# Planning candidate illumination evidence

Planning accepts `POST /api/planning/runs/{id}/illumination` with an exact positive
`assumptionsVersion`, for ADMIN/OPERATOR/SERVICE. It loads an existing immutable Planning run,
verifies captured geometry/model hashes, and retrieves the selected Mission Definition solar
assumptions. Mission identity/version and the geometry's reference digest must agree.

For each of 1..32 candidates it builds an FD interval query from the recorded activity window,
captured AOI and simulation model's minimum solar elevation. Altitude comes explicitly from
the owner's assumption extent; Tasking's two-dimensional AOI does not invent terrain elevation.
The assumption scope must cover each query. Candidate count bounds remote work per evaluation.

FD is called through `/internal/solar-intervals` with a content-derived idempotency key. Its
response must retain the exact query and selected owner envelope/hash, a valid result identity,
and one of the two conditional outcomes. Mismatched source/query responses are rejected. The
assessment retains the complete FD records with candidate identities and the original run hash.

Local assessment/history, idempotency response and PlanningIlluminationEvaluated outbox event
are written atomically after remote computation. An existing run/version result cannot be
replaced by different content. A same-key retry returns before owner I/O. API/internal
`GET /planning/runs/{id}/illumination/{version}` reads the resulting immutable assessment.
Remote FD results can already exist if a later candidate fails; they remain idempotent owner
artifacts and no partial local assessment is published.

Neither conditional outcome changes overall candidate feasibility or commits a schedule.
The remaining AOI sensor, attitude, resource, booking and live safety/conflict gates still apply.
Automatic worker scheduling of this evaluation and the final combined gate/commit path remain
to be connected; this endpoint provides an operator/service-invoked evidence stage.

Six focused tests across the options, run and illumination suites passed with no failures/errors/
skips (`2026-09-12`, `/private/tmp/msc-planning-illumination.log`). The two new tests mock owner
HTTP/store boundaries and verify candidate-derived queries, conditional evidence retention,
wrong owner revision/query rejection and unchanged overall feasibility. They do not establish
HTTP authorization, DB transaction behavior or deployed cross-service evaluation for this API.
