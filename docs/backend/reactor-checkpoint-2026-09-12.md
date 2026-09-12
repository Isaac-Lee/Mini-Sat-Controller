# Backend regression checkpoint — 2026-09-12

## Acquisition, Product and derived TLE checkpoint

The full reactor passed at code baseline
`a7e34c5f10dd79feacb5087e189e3c109b2a6ec8`: **400 test executions across 84 classes**,
with zero failures, errors or skipped tests. The run finished at 16:35:03 KST on
2026-09-12 in 15 minutes 4 seconds. All 20 reactor modules succeeded. The only
subsequent committed change during this run was documentation (`43adfc7`); compiled
BE sources were unchanged. Concurrent Claude drafts remained outside reactor source paths.

This run used the Java 21, Testcontainers, pinned Orekit archive and
`-Dtest=*Test,*IT -Dsurefire.failIfNoSpecifiedTests=false test` configuration below.
Counts come from individual class results, including inherited tests, counted once in
`/private/tmp/msc-be-reactor-product-tle.log`. Local machine evidence with the log
SHA-256 and per-class results is `.local/be-reactor-product-tle.json`.

This adds Acquisition source import and automatic manifest accounting, Product source
packages and automatic work queues, authenticated byte/index downloads, diagnostic PNG
previews, and derived traditional TLE export to the prior regression checkpoint.
Deployed behavior is separately documented in the corresponding feature verification
sections. A successful reactor does not prove complete AOI/attitude feasibility,
production schedule commitment, final release/Space Link dispatch, request-bound
quality fulfillment or the complete operational simulator flow. Those remain required
under the [accepted completion scope](simulation-completion-scope.md).

## Automatic illumination checkpoint

The full reactor passed at source commit
`5d15ac181d20dc69d5722f11eafc63a922c0b40f`: **327 test executions across 73 classes**,
with zero failures, errors or skipped tests. All 18 modules succeeded in 4 minutes
12 seconds. This count includes inherited persistence cases executed in the automatic
illumination worker test class; it is an execution count, not 327 unique scenarios.

The command selected `*Test,*IT` with the same Java 21, local Maven repository, real
Testcontainers and pinned Orekit archive described below. BE Java sources were unchanged
during execution. Per-class results were counted once from
`/private/tmp/msc-be-reactor-automatic-illumination.log`; machine evidence is
`.local/be-reactor-automatic-illumination.json`.

This supersedes the prior 308-test checkpoint for current source coverage and adds current
authority diagnostics, conditional solar intervals, versioned solar assumptions, FD interval
APIs, Planning assessment persistence and automatic illumination work. Separately, the
[deployed automatic flow](planning-illumination.md) passed from a new synthetic request
through scheduled evaluation with two Planning replicas. Neither result proves the remaining
AOI/attitude feasibility, operational schedule commitment, final release, Space Link or
payload/product delivery requirements.

## Previous checkpoint

The full current Maven reactor passed at source commit
`9a3f68cc38fc81c4f72d9791c2ab514a406c76d6`: **308 tests across 67 classes**, with zero
failures, errors or skipped tests. All 18 modules, including the parent and ten independent
service modules, succeeded. Total elapsed time was 4 minutes 13 seconds.

The run used `-Dtest=*Test,*IT -Dsurefire.failIfNoSpecifiedTests=false test`, Java 21,
the existing local Maven repository, real Testcontainers PostgreSQL/RabbitMQ fixtures,
and the pinned UTC/EOP Orekit archive with SHA-256
`ddfd02ae655ba0ac9d5430146a00a2941405983a081184e761d56e8a69973be1`.
Counts come from per-class results in `/private/tmp/msc-be-reactor-current.log`, not
duplicated Maven summary totals or old XML reports. Java sources did not change during
the run. Local machine-readable evidence is `.local/be-reactor-current.json`.

This checkpoint includes simulator execution/reception state, Control evidence intake,
command preparation, human approvals, catalog approval checks, current schedule diagnostics,
versioned authority policy, rectangular illumination, and the previous backend regressions.
It supersedes the older 270-test checkpoint for current source coverage.

Separately, the latest Control image was deployed to the local kind cluster and the deployed
approval/schedule/preparation/evidence verification chain passed. All ten service Deployments
remained ready, with Planning at two replicas. The latest catalog approval check has deployed
denial coverage and deterministic-clock DB lifecycle coverage, not positive production
schedule-to-command approval proof.

Passing these tests does not complete the overall backend objective. Production schedule
feasibility/commit/revalidation, final release orchestration, Space Link dispatch, complete
satellite/ground-station simulation and acquisition/product delivery remain unfinished.
