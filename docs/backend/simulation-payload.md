# Onboard simulated payload materialization

An applied IMAGE command now creates an immutable `simulation-payload-intent` in the same
transaction as its execution ledger, scenario clock and reservoir effect. Rejected, pending,
unsupported and DOWNLINK commands do not create image payloads. Existing completed commands
are not backfilled. The intent binds scenario, command, pinned catalog hash, completion tick,
declared generated megabytes and format `MSC_SIMULATED_RAW_U8_V1`.

With S3 enabled, a scheduled simulator worker discovers intents into its owned V3 work table,
claims one using PostgreSQL `FOR UPDATE SKIP LOCKED`, and streams deterministic raw unsigned
8-bit test samples to its object storage outside any DB transaction. Decimal megabytes map
exactly to whole bytes; the supported per-command range is 1 through 67,108,864 bytes. Zero,
fractional-byte and larger declarations are explicitly rejected, never truncated or rounded.
This is a bounded generator implementation limit, not a spacecraft payload specification.
The pattern repeats the SHA-256 bytes of the canonical intent. It is synthetic test content,
not Earth imagery, a sensor model, a quicklook or a mission L0 product.

ObjectStorage uses content-derived immutable keys. The manifest retains source/hash, exact
byte count, content SHA-256, object reference, SIMULATION environment and ONBOARD location.
Manifest/history and STORED queue completion commit together under the live lease token.
Writes use a five-minute lease; expired work is reclaimable and retryable storage/database
failures wait 30 seconds. Upload success followed by DB failure can leave an unreferenced
object; a retry regenerates identical content and reuses its content key. It cannot publish
a duplicate manifest through an expired token. S3-disabled deployments still retain intents;
they do not pretend that objects were stored.

SERVICE can read stored metadata at `GET /internal/simulation/payloads/{intentId}`. The intent
ID is the canonical JSON fingerprint of `[scenarioId, commandId]`. No event announces ground
reception: materialization is an onboard fact. Downlink delivery, gap accounting, acquisition,
product generation and fulfillment are still separate unfinished steps. Modeled DOWNLINK
reservoir drain does not delete these retained source objects or prove their reception.

The worker and schema are source changes pending deployment. Tests use real PostgreSQL and a
mock object adapter that consumes the complete stream; they do not establish actual MinIO
write/read or deployed worker execution. The existing scenario API integration test checks
intent creation alongside IMAGE effects and rollback on a failed scenario update.

The SERVICE-only `/internal/simulation/payloads/{intentId}/content` endpoint streams the
stored bytes for simulator adapter access with explicit SIMULATION/ONBOARD headers. This
internal read does not model link availability or create any ground-reception evidence.

Twenty focused tests passed across operation effects, scenario HTTP/DB integration and
payload persistence (2026-09-12, `/private/tmp/msc-simulation-payload.log`). Payload tests cover
exact decimal byte count/hash, no DB transaction during upload, durable completion, stale
lease fencing, storage failure, metadata rollback after upload with identical retry bytes,
and rejection of unsupported sizes. The content streaming endpoint requires deployed S3
verification in addition to these checks.
