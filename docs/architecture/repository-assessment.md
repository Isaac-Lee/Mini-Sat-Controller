# Repository assessment — Architecture Foundation v0.1

## Current continuation status

The user has resolved the prerequisite: **Java 21 LTS, Maven, JUnit 5, modular
monolith first**. See [ADR-0001](adr/0001-java-toolchain.md).
The continuation re-inspected the same branch and found the original request,
the previous two assessment documents, and the added Java continuation request.
There was still no code/toolchain or conflicting convention before bootstrap.

Four Maven modules now contain the foundation: domain, ports, infrastructure and
application. Maven Wrapper pins Maven 3.9.9 and its distribution checksum; compiler,
test and lifecycle plugins are pinned. JDK 21 is enforced. Domain has no production
library dependency; application tests alone add ArchUnit for bytecode boundary checks.

The initial host had no discoverable JDK/Maven installation. Verification uses a
Temurin Java 21 JDK extracted under a temporary directory; no global runtime,
framework or database was installed. Contributors must supply JDK 21 via JAVA_HOME.
The original request documents remain unchanged. README now links setup and docs.
No remote change, commit or push is part of this continuation.

The deterministic proof, immutable schedule/estimate models, ports and architectural
ADRs are implemented. See [overview](overview.md) for current scope and
[verification](verification.md) for exact checks and limitations. The assessment
below is retained as the **historical pre-bootstrap finding**, not a current blocker.

## Historical status before the Java decision

Assessed on 2026-09-11 against local baseline commit `58c5d39` (`Initial commit`).
Working branch: `codex/architecture-foundation-v0.1`.

**Repository assessment is complete; Architecture Foundation v0.1 implementation is not complete.**
No programming language or toolchain has been established. Implementation stops
at the explicit prerequisite in section 1 of the supplied task:

> If the repository does not yet establish a language/toolchain, document that fact and stop before making a large technology choice.

## Evidence and structure at initial inspection

The complete working-tree file inventory, excluding Git internals, before changes was:

```text
README.md                 # tracked; only the repository title
Codex 요청 사항.md        # existing untracked implementation request
```

`git ls-tree -r --name-only HEAD` lists only `README.md`.
`git status --short --branch` initially showed `main...origin/main` and the
untracked request document. The local remote configuration points to
`https://github.com/Isaac-Lee/Mini-Sat-Controller.git`; no remote refresh was performed.
Both existing documents were read and left unchanged. No applicable `AGENTS.md`
was found in the repository or inspected parent directories.

| Area | Observed state |
| --- | --- |
| Language, runtime, compiler | Not established |
| Frameworks and dependencies | None declared |
| Build and package manifests | Absent |
| Source modules and entry points | Absent |
| Tests and architecture checks | Absent |
| CI workflows | Absent |
| Deployment configuration | Absent |
| Existing executable functionality | None found |
| Architecture documentation | Request document only; no implemented conventions |

The machine's installed tools are not evidence of a repository technology decision.

## Architectural strengths, problems, and conflicts

There is no legacy code or framework coupling to preserve or migrate. The supplied
request establishes explicit domain boundaries and safety distinctions, but none
are currently enforced by code or tests. There are no overlapping domain types,
existing aggregates, protocol implementations, or frontend to adapt.

The implementation requirements for domain skeletons, ports, a deterministic
Daejeon vertical slice, and tests require a language/build/test toolchain that
does not exist yet. Section 1 explicitly prevents silently choosing one. This is
a prerequisite gap rather than a conflict with existing code.

The prompt's high-level `DOMAIN → APPLICATION → PORTS → INFRASTRUCTURE` diagram
must not be implemented as outward source imports: its explicit dependency rules
require domain independence, with infrastructure implementing inward-facing
contracts. The exact package layout remains pending the toolchain decision.

## Agreed constraints to carry into implementation

These are requirements from the supplied task, not claims of implemented behavior:

- DDD boundaries precede service deployment boundaries; use a modular monolith.
- `MissionSchedule` owns schedule consistency and immutable version history;
  `PlanCandidate` remains a proposal, and assignments use IDs/references.
- Planning schedules approved `ActivityDefinition` instances; CCSDS belongs behind
  Space Link ports/adapters, outside the core domain.
- Observations, state projections, estimates, predictions, execution beliefs,
  and confirmed facts stay distinct. `UNKNOWN` is a valid outcome.
- Immutable estimates have separate operational designation pointers.
- Physical time is TAI-oriented; current time comes through `Clock`;
  conversions use versioned reference data and explicit boundaries.
- User AUTO preference does not grant command authority or bypass safety gates.
- Production and simulation adapters use the same ports.
- Payload bytes remain outside aggregates and events.

Formal ADRs and the remaining architecture documents are deferred with the
implementation; no technology ADR has been accepted by this assessment.

## Concise implementation plan after the prerequisite is resolved

1. Record the agreed language, runtime/version policy, build/package tool, and
   test runner in an ADR; establish the smallest buildable baseline.
2. Write the requested architecture documents and nine agreed architecture ADRs,
   adapting package boundaries to that toolchain.
3. Implement meaningful immutable domain types and ports, with deterministic
   fixtures for intent → request → planning run → candidate → committed assignment.
4. Add the seven required behavior checks plus lightweight dependency checks;
   build, run the full suite, and review the diff for accidental coupling.

This plan does not authorize selecting unresolved technologies. See
[Open decisions](open-decisions.md).

## Initial verification and delivery limits (superseded by continuation)

Repository inventory, baseline, branch, and document changes can be verified with
Git and filesystem inspection. No build or test command exists, so no application
build, test suite, or vertical slice has run or passed. No domain skeleton, port,
deployable, dependency, or runtime was introduced. No numeric Kubernetes resource
requirements are assigned without a workload model.

The full section 39 definition of done remains unmet. Work stops here under
section 1; the next task is to decide and record the minimal implementation
language/toolchain before resuming Foundation v0.1.
