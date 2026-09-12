# Catalog approval requirements

Control exposes `POST /api/command-loads/{id}/catalog-approval-check` and its `/internal`
equivalent for ADMIN/OPERATOR/SERVICE. It derives every requirement from the prepared load's
immutable owner-sourced catalog entries and reads current persisted human approvals. It
accepts no caller-supplied approvals, actor identities or requirement overrides.

The shared domain predicate applies all requirements together. AUTO_ALLOWED does not erase
HUMAN_APPROVAL, TWO_PERSON_APPROVAL or POLICY_APPROVAL; policy and human approvals are
independent conditions. AUTO_FORBIDDEN always blocks, and an empty requirement set is unknown.
Only non-revoked, unexpired approvals with the exact load binding count, and human actors
are counted distinctly. The original single-requirement release policy uses this same predicate.

Control reads approvals under the same database lock used by grant/revoke/renew. Its response
includes the load binding, evaluation time, requirements, approval revisions and failure reasons.
The commit deadline is checked as well. Every call reevaluates current approvals rather than
replaying an earlier successful check. No approval is synthesized from a catalog requirement.

This checks catalog minimum requirements only. Current Mission Definition authority, phase,
mode, safety, schedule, bookings and resource conditions must still be included in final release
orchestration. An empty reason set is not an authorization reference or a transmission permit;
the result can become stale after the transaction ends. POLICY_APPROVAL remains unsatisfied
until a trusted policy-approval producer exists; human approval does not stand in for it.

Tests exercise mixed policy/two-human requirements, forbidden/unknown authority and the existing
release predicates. The Control DB integration test now checks persisted grants, revocation of
both actors, renewal and expiry through this API's controller with a deterministic clock.
Deployment of the new check endpoint and positive HTTP verification remain pending.

The focused run passed 22 tests across four classes with no failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-catalog-approval-check.log`).
