package msc.services.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SimulationProductContentTest extends SimulationProductTest {
  byte[] png;

  SimulationProductContentApi contentApi() throws Exception {
    api.create(request, "create", actor);
    when(objects.read("s3://msc-product/" + hash))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return new ByteArrayInputStream(bytes);
            });
    when(objects.write(eq("image/png"), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              png = ((InputStream) call.getArgument(1)).readAllBytes();
              return "s3://msc-product/"
                  + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
            });
    return new SimulationProductContentApi(store, objects, json);
  }

  @Test
  void streamsOwnedSourceAndRejectsUnknownMembership() throws Exception {
    var content = contentApi();
    var response = content.content(id, receiptId);
    var output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(bytes, output.toByteArray());
    assertEquals(bytes.length, response.getHeaders().getContentLength());
    assertEquals("\"" + hash + "\"", response.getHeaders().getETag());
    assertThrows(ApiException.class, () -> content.content(id, "outside"));
    String reference = api.read(id).body().manifestObjectReference();
    when(objects.read(reference))
        .thenReturn(
            new ByteArrayInputStream(
                json.write(index).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var indexResponse = content.index(id);
    output.reset();
    indexResponse.getBody().writeTo(output);
    assertEquals(
        json.fingerprint(index),
        json.fingerprint(
            json.read(
                output.toString(java.nio.charset.StandardCharsets.UTF_8),
                com.fasterxml.jackson.databind.JsonNode.class)));
  }

  @Test
  void previewPreservesSampleValuesAndExplicitLayoutAndReplays() throws Exception {
    var content = contentApi();
    var saved = content.preview(id, receiptId, "preview", actor);
    var preview = content.readPreview(id, receiptId).body();
    assertEquals("NONE", preview.georeferencing());
    assertEquals(4, preview.validSamples());
    assertEquals(4, preview.width());
    assertEquals(1, preview.height());
    var image = ImageIO.read(new ByteArrayInputStream(png));
    for (int i = 0; i < 4; i++) assertEquals(bytes[i], image.getRaster().getSample(i, 0, 0));
    when(objects.read(preview.objectReference())).thenReturn(new ByteArrayInputStream(png));
    var response = content.previewContent(id, receiptId);
    var output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(png, output.toByteArray());
    assertEquals(
        "SYNTHETIC_BYTE_PREVIEW_NOT_EARTH_IMAGERY",
        response.getHeaders().getFirst("X-MSC-Purpose"));
    clearInvocations(objects);
    assertEquals(
        json.fingerprint(saved),
        json.fingerprint(content.preview(id, receiptId, "preview", actor)));
    content.preview(id, receiptId, "again", actor);
    verifyNoInteractions(objects);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='SimulationBytePreviewCreated'",
            Integer.class));
  }

  @Test
  void corruptAndTruncatedSourcesNeverCreatePreview() throws Exception {
    var content = contentApi();
    for (byte[] invalid : new byte[][] {{4, 3, 2, 1}, {1, 2, 3}, {1, 2, 3, 4, 5}}) {
      doReturn(new ByteArrayInputStream(invalid)).when(objects).read("s3://msc-product/" + hash);
      assertThrows(ApiException.class, () -> content.preview(id, receiptId, "bad", actor));
    }
    verify(objects, never()).write(eq("image/png"), any());
    assertTrue(store.list("simulation-byte-preview", 10).isEmpty());
  }

  @Test
  void validatesBytesBeyondBoundedPreviewAndCapsPixels() throws Exception {
    var content = contentApi();
    byte[] raw = new byte[65537];
    for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    var old = api.read(id);
    var p = old.body();
    String reference = "s3://msc-product/" + digest;
    store.transaction(
        () ->
            store.update(
                "simulation-source-product",
                id.toString(),
                old.version(),
                new SimulationProductApi.Product(
                    p.acquisitionManifest(),
                    p.acquisitionManifestSha256(),
                    List.of(
                        new SimulationProductApi.Artifact(
                            receiptId, "b".repeat(64), raw.length, digest, reference)),
                    p.manifestObjectReference(),
                    raw.length,
                    p.environment(),
                    p.format(),
                    p.status())));
    byte[] corrupt = raw.clone();
    corrupt[65536] = 1;
    doReturn(new ByteArrayInputStream(corrupt)).when(objects).read(reference);
    assertThrows(ApiException.class, () -> content.preview(id, receiptId, "bounded", actor));
    doReturn(new ByteArrayInputStream(raw)).when(objects).read(reference);
    content.preview(id, receiptId, "bounded", actor);
    var preview = content.readPreview(id, receiptId).body();
    assertEquals(65536, preview.validSamples());
    assertEquals(65537, preview.sourceByteCount());
    assertEquals(256, preview.width());
    assertEquals(256, preview.height());
    var image = ImageIO.read(new ByteArrayInputStream(png));
    assertEquals(255, image.getRaster().getSample(255, 255, 0));
  }

  @Test
  void failedPreviewEventRollsBackAndRetries() throws Exception {
    var content = contentApi();
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_preview CHECK (event_type <>"
            + " 'SimulationBytePreviewCreated')");
    try {
      assertThrows(RuntimeException.class, () -> content.preview(id, receiptId, "retry", actor));
      assertTrue(store.list("simulation-byte-preview", 10).isEmpty());
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_preview");
    }
    byte[] first = png.clone();
    content.preview(id, receiptId, "retry", actor);
    assertArrayEquals(first, png);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='SimulationBytePreviewCreated'",
            Integer.class));
  }
}
