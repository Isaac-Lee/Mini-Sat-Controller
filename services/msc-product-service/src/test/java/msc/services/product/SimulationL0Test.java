package msc.services.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import msc.contracts.TaskingContracts.QualityEvidence;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SimulationL0Test extends SimulationProductTest {
  final SimulationL0Api.Create binding = new SimulationL0Api.Create("request-1", 2, 0.5, 0.1);
  final Map<String, byte[]> stored = new HashMap<>();
  SimulationL0Api l0;

  void l0Setup() throws Exception {
    api.create(request, "create", actor);
    when(objects.read(anyString()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              String reference = call.getArgument(0);
              if (reference.equals("s3://msc-product/" + hash))
                return new ByteArrayInputStream(bytes);
              return new ByteArrayInputStream(stored.get(reference));
            });
    when(objects.write(eq("image/png"), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              byte[] png = ((InputStream) call.getArgument(1)).readAllBytes();
              String reference =
                  "s3://msc-product/"
                      + HexFormat.of()
                          .formatHex(MessageDigest.getInstance("SHA-256").digest(png));
              stored.put(reference, png);
              return reference;
            });
    when(http.get(eq("tasking"), eq("/internal/requests/request-1"), eq(JsonNode.class)))
        .thenReturn(json.tree(Map.of("request", Map.of("revision", 2, "status", "SCHEDULED"))));
    l0 = new SimulationL0Api(store, http, objects, json, new SimulationProductContentApi(store, objects, json));
  }

  QualityEvidence published() {
    var payload =
        db.queryForObject(
            "SELECT envelope::text FROM outbox WHERE event_type='ProductQualityAssessed'", String.class);
    return json.convert(json.read(payload, JsonNode.class).path("payload"), QualityEvidence.class);
  }

  @Test
  void buildsL0AndPublishesIntegrityOnlySyntheticAssessmentOnce() throws Exception {
    l0Setup();
    var saved = l0.create(id, binding, "l0", actor);
    var product = l0.read(id).body();
    assertEquals("SIMULATION", product.environment());
    assertEquals(SimulationL0Api.SCOPE, product.scope());
    assertEquals(id.toString(), product.l0().acquisitionId().value());
    assertEquals(api.read(id).body().manifestObjectReference(), product.l0().instrumentSourceManifest());
    var look = product.quicklooks().getFirst();
    assertEquals(hash, look.sourceSha256());
    assertEquals(4, look.width());
    assertEquals(1, look.height());
    var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(stored.get(look.objectReference())));
    for (int i = 0; i < 4; i++) assertEquals(bytes[i], image.getRaster().getSample(i, 0, 0));
    // The assessment report object is the last JSON written (captured by the parent mock).
    assertEquals(SimulationL0Api.SCOPE, index.path("scope").asText());
    assertEquals("SIMULATION_DECLARED_NOT_MEASURED", index.path("coverageBasis").asText());
    assertTrue(index.path("notAssessed").toString().contains("PHYSICAL_AOI_COVERAGE"));
    assertTrue(index.path("notAssessed").toString().contains("RADIOMETRIC_QUALITY"));
    var evidence = published();
    assertEquals("request-1", evidence.requestId());
    assertEquals(2, evidence.revision());
    assertEquals(0.5, evidence.unionCoverageFraction());
    assertEquals(SimulationL0Api.SCOPE, evidence.scope());
    assertEquals("SIMULATION", evidence.environment());
    assertEquals(product.assessmentReference(), evidence.assessmentReference());
    clearInvocations(http, objects);
    assertEquals(json.fingerprint(saved), json.fingerprint(l0.create(id, binding, "l0", actor)));
    l0.create(id, binding, "other-key", actor);
    verifyNoInteractions(http, objects);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='ProductQualityAssessed'",
            Integer.class));
    assertThrows(
        ApiException.class,
        () -> l0.create(id, new SimulationL0Api.Create("request-1", 2, 1, 0.1), "rebind", actor));
    var response = l0.quicklook(id, receiptId);
    var output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);
    assertArrayEquals(stored.get(look.objectReference()), output.toByteArray());
    assertEquals(
        "SYNTHETIC_DIAGNOSTIC_QUICKLOOK_NOT_EARTH_IMAGERY",
        response.getHeaders().getFirst("X-MSC-Purpose"));
  }

  @Test
  void corruptSourceOrUndecodableStoredQuicklookPublishesNothing() throws Exception {
    l0Setup();
    doReturn(new ByteArrayInputStream(new byte[] {4, 3, 2, 1}))
        .when(objects)
        .read("s3://msc-product/" + hash);
    assertThrows(ApiException.class, () -> l0.create(id, binding, "corrupt", actor));
    doAnswer(call -> new ByteArrayInputStream(bytes)).when(objects).read("s3://msc-product/" + hash);
    doReturn("s3://msc-product/broken").when(objects).write(eq("image/png"), any());
    stored.put("s3://msc-product/broken", new byte[] {1, 2, 3});
    assertThrows(ApiException.class, () -> l0.create(id, binding, "broken", actor));
    assertTrue(store.list("simulation-l0-product", 10).isEmpty());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='ProductQualityAssessed'",
            Integer.class));
  }

  @Test
  void staleOrClosedRequestRevisionIsRejectedBeforeObjectWrites() throws Exception {
    l0Setup();
    when(http.get(eq("tasking"), eq("/internal/requests/request-1"), eq(JsonNode.class)))
        .thenReturn(json.tree(Map.of("request", Map.of("revision", 3, "status", "SCHEDULED"))));
    assertThrows(ApiException.class, () -> l0.create(id, binding, "stale", actor));
    when(http.get(eq("tasking"), eq("/internal/requests/request-1"), eq(JsonNode.class)))
        .thenReturn(json.tree(Map.of("request", Map.of("revision", 2, "status", "FULFILLED"))));
    assertThrows(ApiException.class, () -> l0.create(id, binding, "closed", actor));
    verify(objects, never()).write(eq("image/png"), any());
    assertThrows(
        IllegalArgumentException.class, () -> new SimulationL0Api.Create("request-1", 2, 1.5, 0));
  }

  @Test
  void failedAssessmentEventRollsBackL0AndRetryPublishesOnce() throws Exception {
    l0Setup();
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_quality CHECK (event_type <>"
            + " 'ProductQualityAssessed')");
    try {
      assertThrows(RuntimeException.class, () -> l0.create(id, binding, "retry", actor));
      assertTrue(store.list("simulation-l0-product", 10).isEmpty());
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_quality");
    }
    l0.create(id, binding, "retry", actor);
    assertEquals(SimulationL0Api.SCOPE, published().scope());
  }
}
