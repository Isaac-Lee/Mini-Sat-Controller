# Mini-Sat-Controller

## License / 라이선스

This project uses the custom [Mini-Sat-Controller Educational Noncommercial
License 1.0](LICENSE). It is source-available with use restrictions, not an
OSI-approved open-source license.

- **Noncommercial educational use only:** personal learning, teaching,
  coursework, and educational simulations are permitted.
- **Modification is allowed**, with notices identifying the changes and their authors.
- **Attribution is mandatory:** credit Mini-Sat-Controller and its original
  author, **Isaac-Lee**, with the [original repository link](https://github.com/Isaac-Lee/Mini-Sat-Controller).
  Preserve existing contributor notices and include the full license with distributions.
- **Commercial use and non-educational use are prohibited** without separate
  written permission. Paid courses/training, commercial services, internal
  business use, and production satellite operations are not permitted by this license.
- Redistributions and modifications retain these restrictions on the original
  Software. Separately licensed dependencies and data retain their own terms.

**비상업적 교육 목적으로만 이용할 수 있으며 코드 수정은 허용됩니다.**
원작자 **Isaac-Lee**, 프로젝트명과 원본 저장소 링크를 표기하고, 저작권·라이선스
고지를 보존해야 합니다. 재배포 시 LICENSE 전문을 포함하고 수정 사항과 수정자를
표시해야 합니다. 상업적 이용(유료 강의·교육 포함)과 교육 외 목적의 이용은 별도
서면 허가가 필요합니다. 제3자 코드·데이터에는 해당 자료의 라이선스가 적용됩니다.
이 요약보다 [영문 LICENSE 전문](LICENSE)이 우선합니다.

MSC is an intent-driven Earth Observation mission operations system. This repository
contains the Java architecture foundation and an in-progress MSA backend. Independent
mission-definition, Flight Dynamics, tasking, reference-data, ground-operations, Monitoring, Anomaly, Planning intake and station simulator services now exercise
real HTTP, PostgreSQL, RabbitMQ, S3 and Orekit. The full satellite operating flow is not yet complete.

See the [ConOps draft (Korean)](docs/conops.md) for the proposed operational concept.

See the [backend implementation plan](docs/backend/implementation-plan.md),
[current verification evidence](docs/backend/runtime-verification.md), and
[local service instructions](docs/backend/local-runtime.md).

The first public tracking target is **SPACEEYE-T1 (NORAD 63229)**.
[Public orbit collection and SGP4 prediction](docs/backend/public-orbits.md) supports
additional NORAD IDs without changing the propagation code.

[Planning input collection](docs/backend/planning-inputs.md) now consumes real request
events and pins approved simulation agility models; the scheduling engine remains in progress.

## Build and test

Install a **JDK 21** and point `JAVA_HOME` to it (on macOS with a registered JDK:
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`). Maven is provided by the wrapper;
first use requires network access to Maven Central. Windows users can use `mvnw.cmd`.

```sh
./mvnw test
./mvnw verify
```

Maven 3.9.9 and build plugin versions are pinned; dependency versions are managed
in the root POM. The build requires Java 21 and Docker for database/broker integration
tests. Domain code remains framework-free; Spring Boot and Orekit are confined to
runtime services and adapters. ArchUnit 1.3.0 is test-only.

`DaejeonImagingTest` wires a VirtualClock and InMemoryScheduleRepository to execute:

```text
MissionIntent("Daejeon") → accepted ObservationRequest → PlanningRun
→ approved-activity PlanCandidate → versioned MissionSchedule Assignment
```

This test uses explicit AOI, opportunity and feasibility fixtures. No physical
validation, networking, real geocoding, commanding, database or UI is involved.
Replanning appends a new schedule version; the earlier version remains unchanged.

## Architecture

Start with [the overview](docs/architecture/overview.md),
[domain model](docs/architecture/domain-model.md),
[accepted ADRs](docs/architecture/adr/README.md), and
[open decisions](docs/architecture/open-decisions.md).
[Verification evidence and limitations](docs/architecture/verification.md) records
the original foundation proof. [ADR-0010](docs/architecture/adr/0010-independent-services.md)
and later accepted ADRs supersede the original monolith deployment direction.
Independent deployable services live under `services/`; core bounded contexts
remain separate packages behind domain types, ports and published contracts.

## Development

See the [MSC AI Development Harness](docs/ai/README.md) and
[operating playbook](docs/ai/70-operating-playbook.md) for development and review policy.
