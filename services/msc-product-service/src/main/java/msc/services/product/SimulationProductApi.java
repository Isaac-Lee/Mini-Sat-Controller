package msc.services.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Lossless synthetic source package. Sensor qualification and request fulfillment are separate. */
@RestController
public class SimulationProductApi {
  public record Create(UUID manifestId) {
    public Create {
      Objects.requireNonNull(manifestId);
    }
  }

  public record Artifact(
      String receiptId, String payloadId, long byteCount, String sha256, String objectReference) {}

  public record Product(
      JsonNode acquisitionManifest,
      String acquisitionManifestSha256,
      List<Artifact> sources,
      String manifestObjectReference,
      long byteCount,
      String environment,
      String format,
      String status) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final ObjectStorage objects;
  private final Json json;

  public SimulationProductApi(
      StateStore store, ServiceHttp http, ObjectStorage objects, Json json) {
    this.store = store;
    this.http = http;
    this.objects = objects;
    this.json = json;
  }

  @PostMapping("/api/products/simulation-source-packages")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode create(
      @RequestBody Create request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    return createForActor(request, key, actor.getName(), null);
  }

  JsonNode createForActor(Create request, String key, String actor, String expectedHash)
      throws IOException {
    String scope = "simulation-product-create:" + actor, id = request.manifestId().toString();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) {
      checkHash(expectedHash, replay.get().path("body").path("acquisitionManifestSha256").asText());
      return replay.get();
    }
    var prior = store.find("simulation-source-product", id, Product.class);
    if (prior.isPresent()) {
      checkHash(expectedHash, prior.get().body().acquisitionManifestSha256());
      return store.idempotent(scope, key, request, () -> prior.get());
    }
    var manifest =
        http.get("acquisition", "/internal/acquisition/simulation-manifests/" + id, JsonNode.class);
    var expected = validate(request, manifest);
    String hash = json.fingerprint(manifest);
    checkHash(expectedHash, hash);
    var artifacts = new ArrayList<Artifact>();
    objects.ensureBucket();
    for (JsonNode source : expected) {
      String receiptId = source.path("planId").asText();
      long size = source.path("byteCount").asLong();
      Path temp = Files.createTempFile("msc-product-source-", ".part");
      try {
        var digest = digest();
        long actual;
        try (var output = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
          actual =
              http.download(
                  "acquisition",
                  "/internal/acquisition/simulation-sources/" + receiptId + "/content",
                  output,
                  size);
        }
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (actual != size
            || Files.size(temp) != size
            || !actualHash.equals(source.path("sha256").asText()))
          throw ApiException.invalid("Source bytes differ from complete acquisition manifest");
        artifacts.add(
            new Artifact(
                receiptId,
                source.path("payloadId").asText(),
                size,
                actualHash,
                objects.writeFile("application/octet-stream", temp)));
      } finally {
        Files.deleteIfExists(temp);
      }
    }
    // This index retains original bytes and owner evidence; it invents no packet layout or
    // calibration.
    var index =
        Map.of(
            "format",
            "MSC_SIMULATED_SOURCE_PACKAGE_V1",
            "environment",
            "SIMULATION",
            "acquisitionManifest",
            manifest,
            "acquisitionManifestSha256",
            hash,
            "sources",
            artifacts,
            "quality",
            Map.of(
                "wholeSourceCompleteness",
                "COMPLETE",
                "packetCompleteness",
                "NOT_ASSESSED",
                "sensorQualification",
                "NOT_ESTABLISHED"));
    String reference;
    try (var input =
        new ByteArrayInputStream(
            json.write(index).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      reference = objects.write("application/json", input);
    }
    var product =
        new Product(
            manifest,
            hash,
            List.copyOf(artifacts),
            reference,
            manifest.path("body").path("receivedBytes").asLong(),
            "SIMULATION",
            "MSC_SIMULATED_SOURCE_PACKAGE_V1",
            "SOURCE_PACKAGE_STORED");
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("simulation-source-product:" + id);
          var existing = store.find("simulation-source-product", id, Product.class);
          if (existing.isPresent()) {
            checkHash(hash, existing.get().body().acquisitionManifestSha256());
            return existing.get();
          }
          var saved = store.create("simulation-source-product", id, product);
          store.event(
              "SimulationSourceProductCreated",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  List<JsonNode> validate(Create request, JsonNode manifest) {
    var body = manifest.path("body");
    var expected = body.path("expected");
    var received = body.path("received");
    if (!request.manifestId().toString().equals(manifest.path("id").asText())
        || !positive(manifest.path("version"))
        || !"SIMULATION".equals(body.path("environment").asText())
        || !"COMPLETE".equals(body.path("completeness").asText())
        || !body.path("missingPlanIds").isArray()
        || !body.path("missingPlanIds").isEmpty()
        || !expected.isArray()
        || expected.isEmpty()
        || expected.size() > 64
        || !received.isObject()
        || received.size() != expected.size())
      throw ApiException.invalid("Complete simulation acquisition manifest required");
    var result = new ArrayList<JsonNode>();
    var ids = new HashSet<String>();
    var payloads = new HashSet<String>();
    long total = 0;
    for (JsonNode item : expected) {
      String id = item.path("planId").asText(), payload = item.path("payloadId").asText();
      var source = received.path(id);
      var value = source.path("body");
      if (!id.matches("[a-f0-9]{64}")
          || !ids.add(id)
          || !payload.matches("[a-f0-9]{64}")
          || !payloads.add(payload)
          || !positive(item.path("byteCount"))
          || item.path("byteCount").asLong() > 67108864
          || !item.path("sha256").asText().matches("[a-f0-9]{64}")
          || !id.equals(source.path("id").asText())
          || !positive(source.path("version"))
          || !"SIMULATION".equals(value.path("environment").asText())
          || !"RAW_SOURCE_STORED".equals(value.path("status").asText())
          || !positive(value.path("byteCount"))
          || value.path("byteCount").asLong() != item.path("byteCount").asLong()
          || !value.path("sha256").asText().equals(item.path("sha256").asText())
          || !json.fingerprint(item.path("plan").path("body"))
              .equals(item.path("planSha256").asText())
          || !value
              .path("receipt")
              .path("body")
              .path("planSha256")
              .asText()
              .equals(item.path("planSha256").asText()))
        throw ApiException.invalid("Acquisition source index mismatch");
      total = Math.addExact(total, item.path("byteCount").asLong());
      result.add(item);
    }
    if (!positive(body.path("expectedBytes"))
        || !positive(body.path("receivedBytes"))
        || body.path("expectedBytes").asLong() != total
        || body.path("receivedBytes").asLong() != total)
      throw ApiException.invalid("Acquisition manifest total mismatch");
    return List.copyOf(result);
  }

  private static boolean positive(JsonNode n) {
    return n.isIntegralNumber() && n.canConvertToLong() && n.asLong() > 0;
  }

  private static void checkHash(String expected, String actual) {
    if (expected != null && !expected.equals(actual))
      throw ApiException.conflict("Acquisition manifest differs from pinned completion");
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @GetMapping({
    "/api/products/simulation-source-packages/{id}",
    "/internal/products/simulation-source-packages/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Product> read(@PathVariable UUID id) {
    return store.require("simulation-source-product", id.toString(), Product.class);
  }
}
