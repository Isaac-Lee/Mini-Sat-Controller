# ADR-0003 — MissionSchedule as the scheduling consistency boundary

Status: Accepted (architecture requirements, 2026-09-11)

## Context

Concurrent requests share spacecraft resources and must not mutate validated historical plans.

## Decision

Identify schedules by spacecraft, TAI planning horizon and version. Commit creates an immutable next version and ScheduleRepository atomically compares the expected head. Assignment references proposal/request/run/activity IDs.

## Consequences

The reference in-memory adapter serializes publication and preserves history. Exclusive overlaps are rejected. Reservoir simulation, durable transactions, horizon ownership and replacement policy remain unresolved; v0.1 replanning only appends.
