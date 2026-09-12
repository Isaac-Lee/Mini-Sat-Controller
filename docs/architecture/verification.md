# Foundation v0.1 verification

Verified locally on 2026-09-11, branch `codex/architecture-foundation-v0.1`.
Repository baseline was `58c5d39`. No commit, push or real spacecraft interaction
is included in this work.

## Toolchain and commands

- Eclipse Temurin JDK **21.0.12.1+1-LTS**, macOS aarch64.
- Maven **3.9.9**, Maven Wrapper **3.3.2** (official only-script distribution).
- JUnit Jupiter **5.11.4**, test-only ArchUnit **1.3.0**.
- No production third-party libraries. ArchUnit's SLF4J API dependency emits a
  harmless no-provider warning in tests; no production logging framework was added.
- Google Java Format **1.24.0** was used once to format the new Java sources from
  a temporary tool JAR. It is not a build/runtime dependency or required gate.

The host initially had no discoverable JDK. The validation shell used:

```sh
export JAVA_HOME=/private/tmp/msc-toolchain/jdk-21.0.12.1+1/Contents/Home
export MAVEN_USER_HOME=/private/tmp/msc-toolchain/maven-user
export MAVEN_OPTS=-Dmaven.repo.local=/private/tmp/msc-toolchain/repository
./mvnw test
```

Those are **local temporary paths**, not repository prerequisites. Developers
should configure their own JDK 21; ordinary Maven cache defaults work. Initial
wrapper/dependency resolution needs network access. No permanent JDK was installed.

The full lifecycle was run as:

```sh
JAVA_HOME=/private/tmp/msc-toolchain/jdk-21.0.12.1+1/Contents/Home \
MAVEN_USER_HOME=/private/tmp/msc-toolchain/maven-user \
./mvnw -Dmaven.repo.local=/private/tmp/msc-toolchain/repository -B -ntp clean verify
```

Final `test` and `clean verify`: **BUILD SUCCESS**, **22 tests**, zero failures,
errors or skipped tests. Domain: 13; application/projection/architecture: 9.
Four library JARs were packaged. `javap -verbose` confirms major version **65**
(Java 21) for MissionSchedule; Maven compiler release 21 and Enforcer apply to
all modules.

Two consecutive clean verify builds on the same JDK/environment produced identical
SHA-256 values for all four JARs, checked with `shasum -a 256 -c`. This proves
same-environment artifact reproducibility; it does not claim byte identity across
different JDK vendors/patch levels. Maven distribution checksum, dependency/plugin
versions and JAR output timestamp are fixed.

## Required behavior coverage

| Requirement | Evidence |
| --- | --- |
| Java 21, Maven, JUnit execution | Enforcer/compiler logs, JUnit Platform Surefire reports, full lifecycle success |
| Request independent of schedule state | MissionScheduleTest.observationRequestLifecycleDoesNotContainSchedulingState; bytecode context rule |
| Assignment uses IDs/references | MissionScheduleTest.assignmentContainsTypedReferencesAndProposalIsNotCommitment |
| Exclusive-resource conflict rejected | MissionScheduleTest.rejectsExclusiveOverlapWithoutChangingHistory |
| Immutable versions/replanning | MissionScheduleTest.adjacentActivitiesAreAllowedAndReplanLeavesHistoryImmutable; Daejeon test retaining repository version 1 |
| Proposal distinct from assignment | MissionScheduleTest.assignmentContainsTypedReferencesAndProposalIsNotCommitment |
| Telemetry distinct from projection | ObservationVsStateTest.delayedObservationDoesNotReplaceProjectionOrBecomeTruth |
| Immutable estimate and separate designation | OperationalDesignationTest.designationChangesWithoutMutatingSolution |
| Replaceable deterministic Clock | DaejeonImagingTest asserts exact run times before/after VirtualClock advancement |
| UNKNOWN distinct from failure | ExecutionBeliefTest.unknownAndDivergenceAreNotFailureOrConfirmation |
| No planning protocol/transport dependency | ArchitectureTest.taskingAndPlanningRespectContextBoundaries; planning code/test source inspection |
| Feasibility is a result, not aggregate | FeasibilityEvaluation is an immutable record held by PlanCandidate within PlanningRun |
| Only approved activity types planned | MissionScheduleTest.unapprovedOrDisallowedActivityCannotBecomeProposal |
| Domain independent of frameworks/infrastructure | No production dependencies in domain POM; ArchitectureTest allowed dependency rules |
| Clock discipline/time semantics | ArchitectureTest.businessBehaviorCannotReadWallClock; MissionTimeTest mixed-scale and overflow tests |
| Single logical writer per key/head | DaejeonImagingTest.concurrentWritersCannotCommitTwoHeadsFromSameVersion; stale writer test |
| Frozen/out-of-horizon/duplicate/wrong spacecraft guards | MissionScheduleTest focused negative cases |
| Pinned inputs and immutable planning outputs | MissionScheduleTest.inputsAndPlanningOutputsAreDefensivelyCopied |
| Safety freeze boundary | ExecutionBeliefTest.criticalAnomalyOrSafeModeFreezesOnlyAffectedSpacecraft |
| Intent-to-assignment proof | DaejeonImagingTest.intentReachesCommittedAssignmentAndReplanPreservesBothVersions |

## Architecture inspection and completion scope

The four POMs enforce the major dependency graph; ArchUnit imports all four
production code locations and asserts that representative classes are present.
An initial `verify` run exposed that directory-only imports missed upstream JARs.
The loader now handles both directories and JAR URLs, retaining fail-on-empty
rules; both test and verify were rerun successfully. No failing test was skipped
or weakened to pass.

The seven requested architecture documents, repository assessment, decision register,
and nine ADRs exist. Closely related language/build/test and DDD/modular decisions
are combined. Domain-model/context-map documentation covers the full set of
bounded contexts and explicitly identifies documented-only extension points.
Open decisions include every requested deferred technology with decision timing.
README preserves its original title and adds setup/navigation. Both original
request documents remain unchanged; no binary architecture images were added.

Ports protect clock, versioned snapshots, schedule publication, spacecraft links,
ground stations, object streaming and numerical computation. Projection, flight
dynamics context, onboard belief, acquisition reference and L0 manifest boundaries
are represented without introducing production integrations or empty modules.

## Deliberate limits

This completes the requested **architecture foundation**, not the operational
system. The following limits must not be inferred away from passing tests:

- In-memory publication is not durable or distributed. Planning-run artifacts are
  returned to the caller, not persisted transactionally with schedule history.
- Replanning appends a version; replacement/preemption and cross-horizon resource
  ownership are not implemented.
- Reservoir/resource trajectories are NOT_EVALUATED, and bookings are not made.
- Activity approval is trusted fixture/catalog metadata; no security infrastructure,
  command compiler/release/signature or real Space Link adapter exists.
- No numerical algorithms, live reference collection, telemetry projection engine,
  payload processing, L0 format, quicklook, production UI, or Kubernetes deployment.
- Time conversion and a real system-clock adapter require validated versioned data;
  no scalar is casually converted from UTC to TAI.
- ProductionJob, Quicklook, broader Flight Dynamics, contingency and mission-map
  behavior are documented instead of filled with unused empty classes.

Recommended next implementation task: implement a synchronous **CommandLoad release
gate** with deterministic policy/safety fakes, proving that UNKNOWN authority,
active freezes and unvalidated schedule resources cannot reach SpacecraftLinkPort.
Do not add real transmission or select signing/HSM technology as part of that task.
