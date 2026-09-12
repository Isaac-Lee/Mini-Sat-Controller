# Versioned commanding authority

Mission Definition owns `POST /api/authority-policies`, restricted to ADMIN. The payload
contains `expectedVersion` and a policy with spacecraft identity, mission definition version,
provenance and up to 1,000 exact rules. Each rule matches action class, mission phase,
spacecraft mode and risk class, yielding the existing domain requirement: AUTO_ALLOWED,
POLICY_APPROVAL, HUMAN_APPROVAL, TWO_PERSON_APPROVAL or AUTO_FORBIDDEN.

Unmatched contexts return AUTO_FORBIDDEN. An empty published policy explicitly forbids all
automatic release. Duplicate contexts are rejected, so ordering cannot resolve contradictory
requirements. Missing owner policy is a missing artifact, not permission to release. No default
SPACEEYE-T1 authority policy is inferred from NORAD data or installed by this change.

Publication checks the configured mission version, compares the expected owner revision,
and atomically stores immutable history, idempotency response and AuthorityPolicyPublished
outbox event under a spacecraft-specific lock. API/internal GET paths expose current policy
and `/versions/{version}` to ADMIN/OPERATOR/SERVICE. A same-key retry preserves the original
response even after a later revision; release must fetch and bind current authority evidence.

The policy implements the existing pure AuthorityPolicy interface. Evaluating a rule is not
an approval or a release permit. Control must obtain action/risk from approved command
definitions and phase/mode from trusted current state, then apply the resulting requirement
with current load approvals and the remaining CommandReleasePolicy conditions. Requester AUTO
preference is absent from the policy input. The Control release orchestration remains to be
connected; this owner API does not accept or dispatch a command.

Tests cover each matching dimension, deny-by-default behavior and duplicate rejection, plus
actual HTTP/DB publication authorization, typed replay, history preservation, current/versioned
reads, stale revision rejection and mission-version mismatch.

Focused verification passed 12 tests across three classes with no failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-authority-policy.log`).

Mission Definition was independently deployed to the local kind cluster. All ten service
Deployments remained ready, with Planning retaining two replicas. The deployed verifier
`scripts/verify-authority-policies.py` passed eight checks: admin-only writes, original replay
after an update, exact historical/current reads, stale revision rejection, mission mismatch
rejection, duplicate-context rejection, requester read denial, and unchanged current state
after failed writes. It creates an isolated synthetic mission and leaves NORAD 63229 authority
unconfigured. Local evidence is `.local/authority-policy-verification.json`.
