package msc.services.tasking;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.TaskingContracts.RequestDetails;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

/** Request-owned completion of an explicitly synthetic V1 workflow, not physical image quality. */
@RestController
public class SimulationResultApi {
  public record Complete(
      long expectedVersion,
      UUID productId,
      String imageLoadId,
      String downlinkLoadId,
      String reviewReference) {
    public Complete {
      if (expectedVersion < 1) throw new IllegalArgumentException("Request version required");
      Objects.requireNonNull(productId);
      for (var text : List.of(imageLoadId, downlinkLoadId, reviewReference))
        msc.domain.shared.Checks.text(text);
      if (imageLoadId.equals(downlinkLoadId))
        throw new IllegalArgumentException("Distinct IMAGE and DOWNLINK loads required");
    }
  }

  public record Source(
      String receiptId,
      String payloadId,
      long byteCount,
      String sha256,
      long previewByteCount,
      String previewSha256,
      String contentPath,
      String previewPath) {}

  public record Result(
      String requestId,
      long requestRevision,
      String environment,
      String status,
      String completionBasis,
      String productId,
      String scenarioId,
      String imageLoadId,
      String downlinkLoadId,
      String reviewer,
      String reviewReference,
      MissionInstant completedAt,
      Map<String, String> sourceHashes,
      List<Source> sources) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final Clock clock;

  public SimulationResultApi(StateStore store, ServiceHttp http, Json json, Clock clock) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  private static String segment(String value) {
    return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw ApiException.invalid(message);
  }

  private JsonNode control(String id, String suffix) {
    return http.get(
        "spacecraft-control",
        "/internal/command-loads/" + segment(id) + "/" + suffix,
        JsonNode.class);
  }

  private JsonNode execution(String requestId, String loadId) {
    var source = control(loadId, "simulation-execution");
    var value = source.path("body");
    boolean requested = false;
    for (var id : value.path("requestIds")) if (requestId.equals(id.asText())) requested = true;
    require(
        loadId.equals(source.path("id").asText())
            && loadId.equals(value.path("loadId").asText())
            && "SIMULATION".equals(value.path("environment").asText())
            && "SIMULATION_EFFECTS_CONFIRMED".equals(value.path("status").asText())
            && requested,
        "Confirmed simulation execution for this request is required");
    return source;
  }

  private JsonNode release(String requestId, long revision, String loadId, String scenario) {
    var source = control(loadId, "simulation-release");
    var value = source.path("body");
    require(
        loadId.equals(source.path("id").asText())
            && "SIMULATION".equals(value.path("environment").asText())
            && scenario.equals(value.path("scenarioId").asText()),
        "Released simulation load mismatch");
    boolean matched = false;
    for (var assignment :
        value.path("prepared").path("sources").path("schedule").path("assignments")) {
      if (!requestId.equals(assignment.path("requestId").path("value").asText())) continue;
      for (var decision : value.path("checks").path("decisions")) {
        var body = decision.path("body");
        if (body.path("requestRevision").asLong() == revision
            && assignment
                .path("candidateId")
                .path("value")
                .asText()
                .equals(body.path("candidateId").asText())
            && assignment.path("runId").path("value").asText().equals(body.path("runId").asText()))
          matched = true;
      }
    }
    require(matched, "Released load belongs to another request revision");
    return source;
  }

  private JsonNode command(JsonNode execution, String commandId, String operation) {
    for (var entry : execution.path("body").path("ledger").path("body").path("entries"))
      if (commandId.equals(entry.path("command").path("id").path("value").asText())) {
        require(
            "EFFECT_APPLIED".equals(entry.path("status").asText())
                && operation.equals(
                    entry.path("catalog").path("template").path("operation").asText()),
            "Expected modeled operation was not applied");
        return entry;
      }
    throw ApiException.invalid("Product source does not belong to the released commands");
  }

  @PostMapping("/api/requests/{id}/simulation-result")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public JsonNode complete(
      @PathVariable String id,
      @RequestBody Complete request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-request-result:" + actor.getName();
    var body = Map.of("requestId", id, "completion", request);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var original = store.require("request", id, RequestDetails.class);
    if (original.version() != request.expectedVersion() || original.body().request().terminal())
      throw ApiException.conflict("Request changed or ended");
    long revision = original.body().request().revision();
    var image = execution(id, request.imageLoadId());
    var downlink = execution(id, request.downlinkLoadId());
    String scenario = image.path("body").path("scenarioId").asText();
    require(
        !scenario.isBlank() && scenario.equals(downlink.path("body").path("scenarioId").asText()),
        "Execution scenarios differ");
    var imageRelease = release(id, revision, request.imageLoadId(), scenario);
    var downlinkRelease = release(id, revision, request.downlinkLoadId(), scenario);
    String productId = request.productId().toString();
    var product =
        http.get(
            "product",
            "/internal/products/simulation-source-packages/" + productId,
            JsonNode.class);
    var value = product.path("body");
    require(
        productId.equals(product.path("id").asText())
            && "SIMULATION".equals(value.path("environment").asText())
            && "SOURCE_PACKAGE_STORED".equals(value.path("status").asText())
            && "MSC_SIMULATED_SOURCE_PACKAGE_V1".equals(value.path("format").asText()),
        "Stored synthetic Product package required");
    var manifest = value.path("acquisitionManifest");
    var manifestBody = manifest.path("body");
    require(
        productId.equals(manifest.path("id").asText())
            && json.fingerprint(manifest).equals(value.path("acquisitionManifestSha256").asText())
            && scenario.equals(manifestBody.path("scenarioId").asText())
            && "SIMULATION".equals(manifestBody.path("environment").asText())
            && "COMPLETE".equals(manifestBody.path("completeness").asText())
            && manifestBody.path("missingPlanIds").isArray()
            && manifestBody.path("missingPlanIds").isEmpty()
            && manifestBody.path("expectedBytes").asLong() > 0
            && manifestBody.path("expectedBytes").asLong()
                == manifestBody.path("receivedBytes").asLong()
            && manifestBody.path("receivedBytes").asLong() == value.path("byteCount").asLong(),
        "Complete matching Acquisition manifest required");
    var sources = new ArrayList<Source>();
    var receiptIds = new HashSet<String>();
    long total = 0;
    require(
        value.path("sources").isArray()
            && !value.path("sources").isEmpty()
            && value.path("sources").size() <= 64
            && value.path("sources").size() == manifestBody.path("expected").size(),
        "Bounded complete source list required");
    for (var source : value.path("sources")) {
      String receipt = source.path("receiptId").asText(),
          payload = source.path("payloadId").asText(),
          hash = source.path("sha256").asText();
      long bytes = source.path("byteCount").asLong();
      require(
          receipt.matches("[a-f0-9]{64}")
              && hash.matches("[a-f0-9]{64}")
              && receiptIds.add(receipt)
              && bytes > 0
              && bytes <= 67108864,
          "Invalid synthetic source identity/size");
      JsonNode expected = null;
      for (var item : manifestBody.path("expected"))
        if (receipt.equals(item.path("planId").asText())) expected = item;
      require(expected != null, "Source missing from Acquisition manifest");
      var plan = expected.path("plan").path("body");
      var allocation = plan.path("request");
      require(
          scenario.equals(allocation.path("scenarioId").asText())
              && request.downlinkLoadId().equals(allocation.path("loadId").asText())
              && payload.equals(allocation.path("payloadId").asText())
              && payload.equals(expected.path("payloadId").asText())
              && hash.equals(expected.path("sha256").asText())
              && bytes == expected.path("byteCount").asLong(),
          "Source allocation does not match this DOWNLINK");
      command(downlink, allocation.path("commandId").asText(), "DOWNLINK");
      var payloadSource = plan.path("payload").path("source");
      var imageCommand = command(image, payloadSource.path("commandId").asText(), "IMAGE");
      require(
          scenario.equals(payloadSource.path("scenarioId").asText())
              && imageCommand.path("completionTick").asLong()
                  == payloadSource.path("completionTick").asLong()
              && imageCommand
                  .path("catalogSha256")
                  .asText()
                  .equals(payloadSource.path("catalogSha256").asText())
              && hash.equals(plan.path("payload").path("sha256").asText())
              && bytes == plan.path("payload").path("byteCount").asLong(),
          "Payload does not match the confirmed IMAGE");
      var preview =
          http.get(
                  "product",
                  "/api/products/simulation-source-packages/"
                      + productId
                      + "/sources/"
                      + receipt
                      + "/preview",
                  JsonNode.class)
              .path("body");
      require(
          productId.equals(preview.path("productId").asText())
              && receipt.equals(preview.path("receiptId").asText())
              && "SIMULATION".equals(preview.path("environment").asText())
              && hash.equals(preview.path("sourceSha256").asText())
              && bytes == preview.path("sourceByteCount").asLong()
              && preview.path("sha256").asText().matches("[a-f0-9]{64}")
              && preview.path("byteCount").asLong() > 0
              && preview.path("byteCount").asLong() <= 67108864,
          "Matching synthetic preview required");
      String base = "/api/requests/" + segment(id) + "/simulation-result/sources/" + receipt;
      sources.add(
          new Source(
              receipt,
              payload,
              bytes,
              hash,
              preview.path("byteCount").asLong(),
              preview.path("sha256").asText(),
              base + "/content",
              base + "/preview"));
      total = Math.addExact(total, bytes);
    }
    require(total == value.path("byteCount").asLong(), "Product source byte count mismatch");
    var hashes =
        Map.of(
            "product",
            json.fingerprint(product),
            "imageExecution",
            json.fingerprint(image),
            "downlinkExecution",
            json.fingerprint(downlink),
            "imageRelease",
            json.fingerprint(imageRelease),
            "downlinkRelease",
            json.fingerprint(downlinkRelease));
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          store.lock("request:" + id);
          var current = store.require("request", id, RequestDetails.class);
          var details = current.body();
          if (current.version() != request.expectedVersion()
              || details.request().revision() != revision
              || details.request().terminal()
              || details
                  .request()
                  .deadline()
                  .filter(d -> clock.now().compareTo(d) >= 0)
                  .isPresent())
            throw ApiException.conflict("Request changed, ended or expired during result checks");
          var result =
              new Result(
                  id,
                  revision,
                  "SIMULATION",
                  "COMPLETE",
                  "V1_FUNCTIONAL_WORKFLOW_PHYSICAL_IMAGE_QUALITY_NOT_ASSESSED",
                  productId,
                  scenario,
                  request.imageLoadId(),
                  request.downlinkLoadId(),
                  actor.getName(),
                  request.reviewReference(),
                  clock.now(),
                  hashes,
                  List.copyOf(sources));
          var saved = store.create("request-simulation-result", id, result);
          store.update(
              "request",
              id,
              current.version(),
              new RequestDetails(
                  details.request().fulfilled(true),
                  details.owner(),
                  details.target(),
                  details.area(),
                  details.criteria(),
                  details.createdAt(),
                  clock.now(),
                  "SIMULATION_V1_COMPLETE: synthetic workflow; physical image quality not assessed",
                  details.clarificationOptions()));
          store.event("SimulationRequestCompleted", id, revision, UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  private StateStore.State<Result> owned(String id, Authentication actor) {
    var request = store.require("request", id, RequestDetails.class).body();
    boolean elevated =
        actor.getAuthorities().stream()
            .anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_OPERATOR").contains(a.getAuthority()));
    if (!elevated && !request.owner().equals(actor.getName()))
      throw ApiException.missing("Request not found");
    return store.require("request-simulation-result", id, Result.class);
  }

  @GetMapping("/api/requests/{id}/simulation-result")
  public StateStore.State<Result> read(@PathVariable String id, Authentication actor) {
    return owned(id, actor);
  }

  private ResponseEntity<StreamingResponseBody> content(
      String id, String receipt, boolean preview, Authentication actor) {
    var result = owned(id, actor).body();
    var source =
        result.sources().stream()
            .filter(s -> s.receiptId().equals(receipt))
            .findFirst()
            .orElseThrow(() -> ApiException.missing("Result source not found"));
    long size = preview ? source.previewByteCount() : source.byteCount();
    String hash = preview ? source.previewSha256() : source.sha256();
    String path =
        "/api/products/simulation-source-packages/"
            + result.productId()
            + "/sources/"
            + segment(receipt)
            + (preview ? "/preview/content" : "/content");
    return ResponseEntity.ok()
        .contentType(preview ? MediaType.IMAGE_PNG : MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(size)
        .header("X-MSC-Environment", "SIMULATION")
        .header("X-MSC-Content-Scope", preview ? "SYNTHETIC_BYTE_PREVIEW" : "SYNTHETIC_RAW_SOURCE")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "inline; filename=\"synthetic-" + receipt + (preview ? ".png" : ".bin") + "\"")
        .eTag('"' + hash + '"')
        .body(
            output -> {
              var digest = digest();
              long count =
                  http.download(
                      "product", path, new java.security.DigestOutputStream(output, digest), size);
              if (count != size || !HexFormat.of().formatHex(digest.digest()).equals(hash))
                throw new java.io.IOException("Synthetic content integrity mismatch");
            });
  }

  private static java.security.MessageDigest digest() {
    try {
      return java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @GetMapping("/api/requests/{id}/simulation-result/sources/{receipt}/content")
  public ResponseEntity<StreamingResponseBody> raw(
      @PathVariable String id, @PathVariable String receipt, Authentication actor) {
    return content(id, receipt, false, actor);
  }

  @GetMapping("/api/requests/{id}/simulation-result/sources/{receipt}/preview")
  public ResponseEntity<StreamingResponseBody> preview(
      @PathVariable String id, @PathVariable String receipt, Authentication actor) {
    return content(id, receipt, true, actor);
  }
}
