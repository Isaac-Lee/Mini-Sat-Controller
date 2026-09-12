# V1 request-bound DOWNLINK schedule

A DOWNLINK operation follows the selected imaging candidate and retains the same request,
revision and source run. It uses the run's pinned DOWNLINK catalog/resource profile and an
existing confirmed Ground Operations booking. This extends the [V1 Control flow](simulation-command-delivery-v1.md).

`POST /api/planning/runs/{runId}/simulation-downlink` (ADMIN/OPERATOR, Idempotency-Key):

```json
{
  "bookingId": "confirmed-booking-id",
  "window": { "start": {"seconds": 1000, "nanos": 0, "scale": "TAI"},
              "end": {"seconds": 1020, "nanos": 0, "scale": "TAI"} },
  "expectedScheduleVersion": 0,
  "reviewReference": "operator review"
}
```

The concrete window must match the published catalog duration, follow imaging and fit inside
that spacecraft's confirmed booking. Resource validation includes existing committed activities
and their pinned profiles, so prior imaging consumption and subsequent downlink drain are counted
in one timeline. The original imaging run and schedule are retained. The operation decision stores a separate
PlanningRun snapshot and candidate under a new run ID, with sourceRunId pointing to imaging. Schedule/catalog/profile,
operation decision and outbox writes commit atomically with the existing spacecraft lock and CAS.

Read the result from `GET /api/planning/simulation-operations/{activityId}`. Use its schedule
key/version and catalog to prepare the DOWNLINK load through Control. Its V1 operation decision
records the original imaging decision, confirmed booking and resource result. Control resolves
that operation from Planning and rechecks the booking before a new delivery attempt. A cancelled
booking prevents delivery. Delivery/ACK uncertainty follows the existing reconciliation flow.

## Guided review

```sh
python3 scripts/verify-planning-search.py --with-runs --with-camera \
  --with-simulation-commit --with-simulation-dispatch --with-v1-downlink \
  --timeout-seconds 180
```

The continuation creates a synthetic station at a propagated position, obtains a real FD contact
prediction and Ground Operations booking, commits the request-bound DOWNLINK, prepares/approves/
delivers it through Control, and allocates the IMAGE payload before execution. It exercises
pending/disconnected UNKNOWN reception, reconnects and verifies received bytes. An Acquisition
manifest completes automatically, Product stores the source package, and the verifier reads its
actual S3 bytes/hash and creates a synthetic byte preview. It cancels the test request afterward.

This is a simulation functionality review. Neither the payload nor preview is Earth imagery.
Product-to-request fulfillment and the final requester-facing review remain separate integration
work; their completion is not implied by a stored Product package.

Verification passed 13 focused PostgreSQL tests (Planning 6, Control 7) at 19:14 KST on
2026-09-12. Final images: `msc-planning:a9b8aab32522cf8246dd9aa5` and
`msc-spacecraft-control:2989850daa6a2e3307a49870`. No full-reactor claim is made for this checkpoint.

Actual API flow passed for request `887aa988-c938-4342-9d42-54e8d820a472`, imaging run
`68dbaacd-f1f9-3fff-adf9-c450e7009777`, operation `ca103835-5cf3-31fa-a8dd-3b39bb40f918`
and confirmed booking `4f178cc8-44f0-4039-8501-5a8b064d2dfe`. Product
`ac5d8f60-e0b9-459e-bfc6-7a838d13af50` contains 1,000,000 verified source bytes with SHA-256
`c14dd1e6a8a32b7e463c5c17241843c311b15c2211ed74ba77c9f11fea9cda05`, identical to the
request-bound IMAGE payload. The generated preview is 256x256 synthetic byte samples.
The verifier cancelled the request after success; these IDs are historical evidence.
