package msc.services.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import msc.contracts.TaskingContracts.QualityEvidence;
import msc.domain.product.L0Product;
import msc.domain.shared.Ids.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Synthetic L0 reconstruction from a stored source package, with diagnostic quicklooks and an
 * integrity-only assessment. Coverage/cloud fractions are simulation-declared by the operator and
 * relayed with scope INTEGRITY_ONLY_SYNTHETIC; nothing here measures imagery or AOI coverage.
 */
@RestController
public class SimulationL0Api {
  static final String SCOPE = "INTEGRITY_ONLY_SYNTHETIC";
  static final String FORMAT = "MSC_SIMULATED_L0_V1";
  private static final Set<String> OPEN = Set.of("ACCEPTED", "SCHEDULED", "PARTIALLY_FULFILLED");

  public record Create(
      String requestId,
      long revision,
      double declaredCoverageFraction,
      double declaredCloudFraction) {
    public Create {
      msc.domain.shared.Checks.text(requestId);
      if (revision < 1
          || !Double.isFinite(declaredCoverageFraction)
          || declaredCoverageFraction < 0
          || declaredCoverageFraction > 1
          || !Double.isFinite(declaredCloudFraction)
          || declaredCloudFraction < 0
          || declaredCloudFraction > 1)
        throw new IllegalArgumentException("Invalid request binding or declared fractions");
    }
  }

  public record Quicklook(
      String receiptId,
      String sourceSha256,
      long sourceByteCount,
      int width,
      int height,
      String objectReference,
      String sha256,
      long byteCount,
      String rendering) {}

  public record Product(
      L0Product l0,
      String sourcePackageId,
      String requestId,
      long revision,
      List<Quicklook> quicklooks,
      String assessmentReference,
      QualityEvidence assessment,
      String format,
      String environment,
      String scope) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final ObjectStorage objects;
  private final Json json;
  private final SimulationProductContentApi content;

  public SimulationL0Api(
      StateStore store,
      ServiceHttp http,
      ObjectStorage objects,
      Json json,
      SimulationProductContentApi content) {
    this.store = store;
    this.http = http;
    this.objects = objects;
    this.json = json;
    this.content = content;
  }

  @PostMapping("/api/products/simulation-source-packages/{id}/l0")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public JsonNode create(
      @PathVariable UUID id,
      @RequestBody Create request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    String scope = "simulation-l0:" + actor.getName(), productId = id.toString();
    var body = Map.of("productId", productId, "binding", request);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var prior = store.find("simulation-l0-product", productId, Product.class);
    if (prior.isPresent()) {
      same(prior.get().body(), request);
      return store.idempotent(scope, key, body, () -> prior.get());
    }
    var source =
        store.require("simulation-source-product", productId, SimulationProductApi.Product.class);
    var pkg = source.body();
    if (!"SIMULATION".equals(pkg.environment())
        || !"SOURCE_PACKAGE_STORED".equals(pkg.status())
        || pkg.sources().isEmpty())
      throw ApiException.invalid("Stored synthetic source package required");
    var target =
        http.get(
                "tasking",
                "/internal/requests/"
                    + org.springframework.web.util.UriUtils.encodePathSegment(
                        request.requestId(), StandardCharsets.UTF_8),
                JsonNode.class)
            .path("request");
    if (target.path("revision").asLong() != request.revision()
        || !OPEN.contains(target.path("status").asText()))
      throw ApiException.conflict("Request revision is not open for fulfillment");
    objects.ensureBucket();
    var quicklooks = new ArrayList<Quicklook>();
    // Object I/O stays outside DB transactions; content-addressed retries reuse objects.
    for (var artifact : pkg.sources()) {
      var rendered = content.render(artifact);
      String reference;
      try (var input = new ByteArrayInputStream(rendered.png())) {
        reference = objects.write("image/png", input);
      }
      quicklooks.add(verify(rendered, reference));
    }
    String manifestId = pkg.acquisitionManifest().path("id").asText();
    // Sorted keys keep the content-addressed report stable across JVMs and retries.
    var report =
        new TreeMap<String, Object>(
            Map.of(
            "format",
            FORMAT,
            "environment",
            "SIMULATION",
            "scope",
            SCOPE,
            "sourcePackageId",
            productId,
            "acquisitionManifestSha256",
            pkg.acquisitionManifestSha256(),
            "checked",
            List.of("SOURCE_BYTE_COUNT", "SOURCE_SHA256", "QUICKLOOK_STORED_SHA256", "PNG_DECODES"),
            "notAssessed",
            List.of(
                "PACKET_COMPLETENESS",
                "RADIOMETRIC_QUALITY",
                "IMAGE_QUALITY",
                "GEOREFERENCING",
                "PHYSICAL_AOI_COVERAGE",
                "PHYSICAL_CLOUD_COVER"),
            "coverageBasis",
            "SIMULATION_DECLARED_NOT_MEASURED",
            "quicklooks",
            quicklooks));
    String assessmentReference;
    try (var input = new ByteArrayInputStream(json.write(report).getBytes(StandardCharsets.UTF_8))) {
      assessmentReference = objects.write("application/json", input);
    }
    var l0 =
        new L0Product(
            new ProductId("simulation-l0:" + productId),
            new AcquisitionId(manifestId),
            pkg.manifestObjectReference(),
            "NOT_ASSESSED:whole-source-only;no-packet-model",
            assessmentReference,
            List.of());
    var evidence =
        new QualityEvidence(
            request.requestId(),
            request.revision(),
            l0.id().value(),
            manifestId,
            request.declaredCoverageFraction(),
            request.declaredCloudFraction(),
            assessmentReference,
            SCOPE,
            "SIMULATION");
    var product =
        new Product(
            l0,
            productId,
            request.requestId(),
            request.revision(),
            List.copyOf(quicklooks),
            assessmentReference,
            evidence,
            FORMAT,
            "SIMULATION",
            SCOPE);
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          store.lock("simulation-l0-product:" + productId);
          var existing = store.find("simulation-l0-product", productId, Product.class);
          if (existing.isPresent()) {
            same(existing.get().body(), request);
            return existing.get();
          }
          var saved = store.create("simulation-l0-product", productId, product);
          store.event(
              "ProductQualityAssessed",
              productId,
              saved.version(),
              UUID.randomUUID(),
              null,
              evidence);
          return saved;
        });
  }

  /** Reads the stored quicklook back: exact size/hash and a decodable PNG of expected size. */
  private Quicklook verify(SimulationProductContentApi.Rendered rendered, String reference)
      throws IOException {
    byte[] stored;
    try (var input = objects.read(reference)) {
      stored = input.readNBytes(rendered.png().length + 1);
    }
    String hash = HexFormat.of().formatHex(SimulationProductContentApi.digest().digest(stored));
    String expected =
        HexFormat.of().formatHex(SimulationProductContentApi.digest().digest(rendered.png()));
    var image = ImageIO.read(new ByteArrayInputStream(stored));
    if (stored.length != rendered.png().length
        || !hash.equals(expected)
        || image == null
        || image.getWidth() != rendered.width()
        || image.getHeight() != rendered.height())
      throw ApiException.invalid("Stored quicklook failed integrity check");
    var source = rendered.source();
    return new Quicklook(
        source.receiptId(),
        source.sha256(),
        source.byteCount(),
        rendered.width(),
        rendered.height(),
        reference,
        hash,
        stored.length,
        "FIRST_65536_BYTES_ROW_MAJOR_U8_ZERO_PADDED_NOT_EARTH_IMAGERY");
  }

  private static void same(Product prior, Create request) {
    var e = prior.assessment();
    if (!prior.requestId().equals(request.requestId())
        || prior.revision() != request.revision()
        || e.unionCoverageFraction() != request.declaredCoverageFraction()
        || e.cloudFraction() != request.declaredCloudFraction())
      throw ApiException.conflict("L0 product already bound to another request assessment");
  }

  @GetMapping({
    "/api/products/simulation-source-packages/{id}/l0",
    "/internal/products/simulation-source-packages/{id}/l0"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Product> read(@PathVariable UUID id) {
    return store.require("simulation-l0-product", id.toString(), Product.class);
  }

  @GetMapping("/api/products/simulation-source-packages/{id}/l0/quicklooks/{receipt}/content")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public org.springframework.http.ResponseEntity<
          org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      quicklook(@PathVariable UUID id, @PathVariable String receipt) {
    var look =
        read(id).body().quicklooks().stream()
            .filter(q -> q.receiptId().equals(receipt))
            .findFirst()
            .orElseThrow(() -> ApiException.missing("Quicklook not part of L0 product"));
    return content.stream(
        look.objectReference(),
        org.springframework.http.MediaType.IMAGE_PNG,
        look.byteCount(),
        look.sha256(),
        "SYNTHETIC_DIAGNOSTIC_QUICKLOOK_NOT_EARTH_IMAGERY");
  }
}
