# Event model

Use synchronous strong consistency for schedule commit, command compilation/release
gates, safety/approval decisions and operational designation changes. Events are
facts recorded after a successful transition, not disguised commands such as
"please schedule". No broker, full event sourcing or outbox storage is implemented.

| Owner | Anticipated facts |
| --- | --- |
| tasking | ObservationRequestAccepted, RequestFulfilled, RequestPartiallyFulfilled |
| planning | PlanningRunStarted, ScheduleVersionCommitted, AssignmentPreempted, AssignmentExpired |
| spacecraft-control | CommandLoadPrepared, CommandLoadApproved, CommandLoadUplinked, OnboardScheduleVerified, OnboardScheduleDivergenceDetected |
| acquisition | AcquisitionExecutionConfirmed, AcquisitionExecutionFailed, AcquisitionOutcomeUnknown, AcquisitionDataComplete |
| flight-dynamics | OperationalOrbitChanged |
| reference-data | ReferenceSnapshotPublished, ReferenceDataStale |
| monitoring | SpacecraftModeChanged, SafeModeDetected |
| anomaly | AnomalyDeclared, PlanningFrozen |
| product-generation | L0ProductCreated |

These names are an event contract catalog, not claims that v0.1 emits all events.
The current use case returns immutable artifacts synchronously. Future event
contracts should contain event ID, type/schema version, occurrence time with scale,
aggregate ID/version, correlation ID, causation ID and provenance references.
Payload bytes and embedded aggregate graphs do not belong in events.

When durable persistence is selected, atomically save the state change and outbox
entry in one transaction. Publish asynchronously, retry with stable event IDs,
and require idempotent consumers/deduplication. Do not emit ScheduleVersionCommitted
before the expected-version commit succeeds. Failed/stale commits produce no fact
of commitment. Delivery order is not spacecraft observation or execution order;
consumers reconcile version/evidence time and preserve UNKNOWN.

Events suit telemetry fan-out, read-model updates, reference-data publication,
replanning triggers, L0 triggers and user notifications. A safe-mode event may
trigger a workflow, but command release must synchronously check the current
safety/authority gate so delivery lag cannot authorize unsafe commanding.
