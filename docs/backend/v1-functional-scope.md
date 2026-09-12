# V1 functional review scope

Accepted user direction, 2026-09-12:

> 너무 완벽한 프로그램을 만들 필요는 없어.
> 1차적으로 내가 모든 기능들을 확인할 수 있는 수준으로 제한해서 마무리해줘.
> 특히 비행역학 쪽은 알고리즘 수정이 빈번하니 나중에 수정해도 괜찮아.

This narrows the current completion goal to a usable first simulation release. It supersedes
older requirements that made advanced numerical proofs or production hardening prerequisites
for reviewing the whole workflow. Preserve the existing MSA/API/owned-storage/K8s architecture.
Do not add services, abstractions or precision models solely for completeness.

## Required for V1

Provide a reproducible, documented way for the user to exercise every main function, with
real API calls and saved results. One guided scenario should connect the following stages:

1. Inspect SPACEEYE-T1 / NORAD 63229 public orbit data and its source epoch; refresh/import and
   view propagated positions or access opportunities using the existing FD algorithms.
2. Submit an observation request and inspect/select its candidate, sampled camera evidence
   and basic resource estimates. Clearly identify the calculation/model as a V1 approximation.
3. Commit a simulation schedule and prepare/approve its command load. The schedule and
   commands must reference the selected request/candidate; do not inject unrelated fixture
   schedules behind the user-visible workflow.
4. Run the spacecraft and ground-station simulators: command execution, telemetry/state,
   station booking, link connection/disconnection and at least one UNKNOWN/reconciliation case.
5. Execute IMAGE and DOWNLINK, retain received bytes in Acquisition, and create a downloadable
   synthetic product/preview. Show the relationship to the original request and its progress.
6. Review the resulting IDs, statuses, timeline and artifact links through existing console/API
   surfaces and a single guided command/runbook. Keep unfinished numerical checks visible.

Existing authenticated APIs, persistent state, history/idempotency and basic request/command
identity checks stay in place. All execution is simulation-only. A V1 result may use a simple
sampled or approximate algorithm without claiming continuous coverage, hardware performance
or operational qualification. A synthetic product must remain labelled synthetic.

## Deferred beyond V1

- Conservative continuous-exposure proofs and precision attitude tracking/settling validation.
- Higher-fidelity FD, maneuver optimization, detailed force/terrain/sensor models and exact image quality.
- Physical satellite or RF qualification; real SPACEEYE instrument specifications.
- Full packet-gap recovery, exhaustive fault matrices, load benchmarks and production hardening.
- Additional service extraction where current service boundaries already let the feature operate.
- Multi-satellite optimization; keep spacecraft identities extensible, but demonstrate one mission.

Use the existing FD adapter boundary so algorithms can be replaced later without rewriting
request, schedule, command, acquisition or product flows. Advanced checks must not become a
new prerequisite for delivering this functional version. Do not label deferred work as tested
or silently convert missing evidence into a scientific claim.

## Current integration gaps to close

Public orbit, pointing, sampled footprints, automatic Planning evidence, telemetry, ground
booking, synthetic execution/downlink, Acquisition and Product APIs already have individual
verification paths. Schedule commitment and Control preparation/approval now use the selected request. V1 Control
delivery, IMAGE execution, lost ACK reconciliation and execution binding have passed live API verification. Ground downlink, Acquisition completion and Product generation now pass for the same request-bound
payload. The remaining priority is requester-facing result lookup and completion status, followed
by the final guided review and completion audit.
Successful isolated scripts alone do not prove that connected V1 scenario is finished.

Completion means the user can run the guided scenario and inspect each stage with persistent
IDs and outputs. Finish the integration and a small useful verification set, then stop expanding
scope. Preserve the unrelated frontend work already present in the checkout.
