package msc.services.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import msc.platform.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Owned product downloads and a bounded synthetic byte preview, never georeferenced imagery. */
@RestController
public class SimulationProductContentApi {
  public record Preview(
      String productId,
      String receiptId,
      String sourceSha256,
      long sourceByteCount,
      int width,
      int height,
      int validSamples,
      String objectReference,
      String sha256,
      long byteCount,
      String environment,
      String rendering,
      String georeferencing) {}

  private final StateStore store;
  private final ObjectStorage objects;
  private final Json json;

  public SimulationProductContentApi(StateStore store, ObjectStorage objects, Json json) {
    this.store = store;
    this.objects = objects;
    this.json = json;
  }

  private SimulationProductApi.Product product(UUID id) {
    return store
        .require("simulation-source-product", id.toString(), SimulationProductApi.Product.class)
        .body();
  }

  private SimulationProductApi.Artifact source(UUID id, String receipt) {
    return product(id).sources().stream()
        .filter(s -> s.receiptId().equals(receipt))
        .findFirst()
        .orElseThrow(() -> ApiException.missing("Source not part of product"));
  }

  private ResponseEntity<StreamingResponseBody> stream(
      String reference, MediaType type, long size, String hash, String purpose) {
    var response =
        ResponseEntity.ok()
            .contentType(type)
            .header("X-MSC-Environment", "SIMULATION")
            .header("X-MSC-Purpose", purpose)
            .header("X-Content-Type-Options", "nosniff");
    if (size >= 0) response.contentLength(size);
    if (hash != null) response.eTag('"' + hash + '"');
    return response.body(
        output -> {
          try (var input = objects.read(reference)) {
            input.transferTo(output);
          }
        });
  }

  @GetMapping({
    "/api/products/simulation-source-packages/{id}/sources/{receipt}/content",
    "/internal/products/simulation-source-packages/{id}/sources/{receipt}/content"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public ResponseEntity<StreamingResponseBody> content(
      @PathVariable UUID id, @PathVariable String receipt) {
    var source = source(id, receipt);
    return stream(
        source.objectReference(),
        MediaType.APPLICATION_OCTET_STREAM,
        source.byteCount(),
        source.sha256(),
        "SYNTHETIC_RAW_SOURCE");
  }

  @GetMapping({
    "/api/products/simulation-source-packages/{id}/index",
    "/internal/products/simulation-source-packages/{id}/index"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public ResponseEntity<StreamingResponseBody> index(@PathVariable UUID id) {
    return stream(
        product(id).manifestObjectReference(),
        MediaType.APPLICATION_JSON,
        -1,
        null,
        "SYNTHETIC_SOURCE_INDEX");
  }

  @PostMapping("/api/products/simulation-source-packages/{id}/sources/{receipt}/preview")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode preview(
      @PathVariable UUID id,
      @PathVariable String receipt,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    String scope = "simulation-byte-preview:" + actor.getName(),
        identity = json.fingerprint(List.of(id.toString(), receipt));
    var request = List.of(id.toString(), receipt);
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var prior = store.find("simulation-byte-preview", identity, Preview.class);
    if (prior.isPresent()) return store.idempotent(scope, key, request, () -> prior.get());
    var source = source(id, receipt);
    if (source.byteCount() < 1 || source.byteCount() > 67108864)
      throw ApiException.invalid("Unsupported source size");
    int count = (int) Math.min(source.byteCount(), 65536);
    byte[] samples = new byte[count];
    var digest = digest();
    long total = 0;
    try (var input = objects.read(source.objectReference())) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (total + n > source.byteCount())
          throw ApiException.invalid("Source exceeds manifest length");
        digest.update(buffer, 0, n);
        if (total < count)
          System.arraycopy(buffer, 0, samples, (int) total, Math.min(n, count - (int) total));
        total += n;
      }
    }
    if (total != source.byteCount()
        || !HexFormat.of().formatHex(digest.digest()).equals(source.sha256()))
      throw ApiException.invalid("Source byte count/hash mismatch");
    int width = Math.min(count, 256), height = (count + width - 1) / width;
    var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    for (int i = 0; i < count; i++)
      image.getRaster().setSample(i % width, i / width, 0, Byte.toUnsignedInt(samples[i]));
    var bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer unavailable");
    byte[] png = bytes.toByteArray();
    String hash = HexFormat.of().formatHex(digest().digest(png));
    objects.ensureBucket();
    String reference;
    try (var input = new ByteArrayInputStream(png)) {
      reference = objects.write("image/png", input);
    }
    var preview =
        new Preview(
            id.toString(),
            receipt,
            source.sha256(),
            source.byteCount(),
            width,
            height,
            count,
            reference,
            hash,
            png.length,
            "SIMULATION",
            "FIRST_65536_BYTES_ROW_MAJOR_U8_ZERO_PADDED",
            "NONE");
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("simulation-byte-preview:" + identity);
          var existing = store.find("simulation-byte-preview", identity, Preview.class);
          if (existing.isPresent()) return existing.get();
          var saved = store.create("simulation-byte-preview", identity, preview);
          store.event(
              "SimulationBytePreviewCreated",
              id.toString(),
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping("/api/products/simulation-source-packages/{id}/sources/{receipt}/preview")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Preview> readPreview(
      @PathVariable UUID id, @PathVariable String receipt) {
    return store.require(
        "simulation-byte-preview",
        json.fingerprint(List.of(id.toString(), receipt)),
        Preview.class);
  }

  @GetMapping("/api/products/simulation-source-packages/{id}/sources/{receipt}/preview/content")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public ResponseEntity<StreamingResponseBody> previewContent(
      @PathVariable UUID id, @PathVariable String receipt) {
    var preview = readPreview(id, receipt).body();
    return stream(
        preview.objectReference(),
        MediaType.IMAGE_PNG,
        preview.byteCount(),
        preview.sha256(),
        "SYNTHETIC_BYTE_PREVIEW_NOT_EARTH_IMAGERY");
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
