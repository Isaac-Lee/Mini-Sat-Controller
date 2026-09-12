package msc.services.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Acquisition-owned exact raw source import, not a qualified L0/product or task fulfillment. */
@RestController
public class SimulationSourceApi {
  public record Import(String receiptId) {
    public Import {
      if (receiptId == null || !receiptId.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Exact simulator receipt ID required");
    }
  }

  public record Source(
      JsonNode receipt,
      String receiptSha256,
      long byteCount,
      String sha256,
      String objectReference,
      String environment,
      String status) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final ObjectStorage objects;
  private final Json json;
  private final SimulationManifestApi manifests;

  public SimulationSourceApi(
      StateStore store,
      ServiceHttp http,
      ObjectStorage objects,
      Json json,
      SimulationManifestApi manifests) {
    this.store = store;
    this.http = http;
    this.objects = objects;
    this.json = json;
    this.manifests = manifests;
  }

  @PostMapping("/api/acquisition/simulation-sources")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode acquire(
      @RequestBody Import request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    return acquireForActor(request, key, actor.getName(), null);
  }

  JsonNode acquireForActor(Import request, String key, String actor, String expectedReceiptHash)
      throws IOException {
    String scope = "simulation-source-import:" + actor;
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) {
      requireReceiptHash(
          expectedReceiptHash, replay.get().path("body").path("receiptSha256").asText());
      return replay.get();
    }
    var prior = store.find("simulation-acquisition-source", request.receiptId(), Source.class);
    if (prior.isPresent()) {
      requireReceiptHash(expectedReceiptHash, prior.get().body().receiptSha256());
      return store.idempotent(scope, key, request, () -> prior.get());
    }
    String path = "/internal/simulation/downlinks/" + request.receiptId();
    var receipt = http.get("simulator", path + "/receipt", JsonNode.class);
    long expected = validate(request, receipt);
    requireReceiptHash(expectedReceiptHash, json.fingerprint(receipt));
    var temporary = Files.createTempFile("msc-acquisition-source-", ".part");
    try {
      var digest = digest();
      long actual;
      try (var output = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
        actual = http.download("simulator", path + "/received-content", output, expected);
      }
      String hash = HexFormat.of().formatHex(digest.digest());
      if (actual != expected
          || Files.size(temporary) != expected
          || !hash.equals(receipt.path("body").path("sha256").asText()))
        throw ApiException.invalid("Received source byte count/hash mismatch");
      objects.ensureBucket();
      String reference = objects.writeFile("application/octet-stream", temporary);
      var source =
          new Source(
              receipt,
              json.fingerprint(receipt),
              expected,
              hash,
              reference,
              "SIMULATION",
              "RAW_SOURCE_STORED");
      return store.idempotent(
          scope,
          key,
          request,
          () -> {
            store.lock("simulation-acquisition-source:" + request.receiptId());
            var existing =
                store.find("simulation-acquisition-source", request.receiptId(), Source.class);
            if (existing.isPresent()) {
              if (!json.fingerprint(existing.get().body()).equals(json.fingerprint(source)))
                throw ApiException.conflict("Immutable source receipt/content changed");
              return existing.get();
            }
            var saved = store.create("simulation-acquisition-source", request.receiptId(), source);
            manifests.sourceStored(request.receiptId());
            store.event(
                "SimulationAcquisitionSourceStored",
                request.receiptId(),
                saved.version(),
                UUID.randomUUID(),
                null,
                saved);
            return saved;
          });
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  long validate(Import request, JsonNode receipt) {
    var body = receipt.path("body");
    var size = body.path("byteCount");
    if (!request.receiptId().equals(receipt.path("id").asText())
        || !request.receiptId().equals(body.path("planId").asText())
        || !receipt.path("version").isIntegralNumber()
        || !receipt.path("version").canConvertToLong()
        || receipt.path("version").asLong() < 1
        || !positiveVersion(body.path("ledgerVersion"))
        || !positiveVersion(body.path("linkVersion"))
        || !"SIMULATION".equals(body.path("environment").asText())
        || !body.path("sha256").asText().matches("[a-f0-9]{64}")
        || !body.path("planSha256").asText().matches("[a-f0-9]{64}")
        || body.path("stationId").asText().isBlank()
        || !size.isIntegralNumber()
        || !size.canConvertToLong()
        || size.asLong() < 1
        || size.asLong() > 64L * 1024 * 1024)
      throw ApiException.invalid("Invalid owner receipt identity/scope/size/hash");
    json.convert(body.path("receivedAt"), MissionInstant.class).requireTai();
    return size.asLong();
  }

  private static void requireReceiptHash(String expected, String actual) {
    if (expected != null && !expected.equals(actual))
      throw ApiException.conflict("Owner receipt differs from received event");
  }

  private static boolean positiveVersion(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @GetMapping({
    "/api/acquisition/simulation-sources/{id}",
    "/internal/acquisition/simulation-sources/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Source> read(@PathVariable String id) {
    return store.require("simulation-acquisition-source", id, Source.class);
  }
}
