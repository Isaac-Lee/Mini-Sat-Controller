# Current schedule checks for prepared loads

Planning adds `POST /api/planning/schedules/current` (also `/internal`) with a ScheduleKey
body. It returns the current owned snapshot, or 404 if no committed head exists. The exact
historical query endpoint remains separate and unchanged.

Control adds `POST /api/command-loads/{id}/schedule-check` (also `/internal`) for
ADMIN/OPERATOR/SERVICE. It reads its immutable prepared load, fetches the current Planning
head on every request, validates the returned schedule structure and key, and reports:

- SCHEDULE_MISSING when the owner returns 404;
- STALE_SCHEDULE when the current version differs;
- SCHEDULE_CONTENT_MISMATCH when content differs despite an identical version;
- DEADLINE_PASSED when the service clock is at or beyond the commit deadline.

The deadline timestamp is obtained after the owner round trip. The response binds the load
and includes the observed current schedule and evaluation time. It is never replayed as an
idempotent old check, and has no state mutation, authorization reference or dispatch effect.
An owner transport/error response other than 404 remains an error rather than being mistaken
for a valid or empty schedule.

This diagnostic is a prerequisite for release orchestration, not a release permit. Planning
can change after the read, so an empty reason set does not prove an atomic release condition.
Final dispatch must still bind/revalidate owner revisions and bookings, safety, authority,
approvals and current resources. The API does not substitute these checks with caller flags.

Focused unit tests use mocked owner/store boundaries to cover repeated live reads, advancement,
same-version changed content, absent schedule and a deadline elapsed during the HTTP round trip.
Planning head/history separation is verified at the repository boundary. Deployment and real
cross-service positive checking remain separate verification steps.

The focused compiler/check/query run passed nine tests with no failures/errors/skips
(`2026-09-12`, local log `/private/tmp/msc-schedule-check.log`). These endpoints have not
yet been rolled out to the running Planning and Control services.
