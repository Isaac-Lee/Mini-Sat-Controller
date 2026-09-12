# Glossary

| Term | Meaning |
| --- | --- |
| MSC | Mini-Sat-Control, an intent-driven Earth Observation mission operations system |
| Bounded context | Logical domain ownership/language boundary; not automatically a service |
| MissionIntent | User-facing observation target input |
| AOI | Area of interest; DaejeonAOI is an explicitly fake resolved reference |
| ObservationRequest | User-request aggregate; contains no scheduling allocation |
| PlanningRun | Immutable planning attempt and its pinned inputs/results |
| Opportunity | Predicted/fixture window where an activity may be possible |
| FeasibilityEvaluation | Immutable planning output, not an aggregate or truth guarantee |
| PlanCandidate | Proposal before commitment |
| DecisionRecord | Candidate selection rationale and provenance link |
| MissionSchedule | Spacecraft × horizon × version consistency boundary |
| ScheduledActivity | Instantiated approved activity definition, time window and resource occupation |
| Assignment | Committed request/candidate/run/activity references within a schedule version |
| ActivityDefinition | Approved operational activity semantics; not an arbitrary command sequence |
| AuthorityPolicy | Action/phase/mode/risk authorization requirements, independent of AUTO |
| Frozen horizon | Boundary before which new activities cannot be added in v0.1 |
| Reservoir resource | Timeline-dependent resource such as battery or storage |
| External reservation | Tentative/confirmed booking outside spacecraft-local resources |
| CommandLoad | Prepared versioned semantic command artifact; not release permission |
| OnboardScheduleModel | Ground belief of loaded onboard schedule, possibly divergent |
| UNKNOWN | Normal lack of sufficient evidence, distinct from failure |
| TelemetryObservation | Timestamped received evidence, not authoritative state |
| SpacecraftOperationalState | Derived belief/freshness projection |
| OrbitSolution | Immutable estimated orbital state with context/provenance |
| OrbitEphemeris | Predicted trajectory samples/coverage manifest |
| OperationalDesignation | Versioned pointer selecting an immutable estimate |
| PropellantEstimate | Estimated mass with model, uncertainty and provenance |
| TAI / UTC / UT1 / TT / GPS | Distinct time scales; tags do not implement conversion |
| OBT | Onboard clock ticks interpreted through a versioned time correlation |
| SnapshotRef | Exact identity/version/source/time reference to immutable data |
| ContactOpportunity | Computed visibility window |
| StationBooking | Tentative/confirmed resource reservation |
| PassSession / PassReport | Actual pass execution identity / its resulting evidence |
| Acquisition | Imaging lifecycle linking commanding, execution belief and data accounting |
| L0Product | Lossless reconstructed instrument-source manifest with gaps/quality |
| Quicklook | Future preview/coverage/cloud evidence; L0 alone is not a user preview |
| Transactional outbox | Future atomic state-plus-event persistence mechanism |
| Space Link | Infrastructure boundary for spacecraft protocol transport, including CCSDS |
