package msc.services.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class SimulationProductWorkerTest extends SimulationProductTest {
  SimulationProductWorker worker() {
    return new SimulationProductWorker(store, db, json, api);
  }

  ServiceEvent event() {
    return new ServiceEvent(
        UUID.randomUUID(),
        1,
        "SimulationAcquisitionDataComplete",
        id.toString(),
        2,
        UUID.randomUUID(),
        null,
        MissionInstant.tai(1000),
        manifest);
  }

  void deliver(SimulationProductWorker worker, ServiceEvent event) {
    store.transaction(
        () -> {
          if (store.receive(event.eventId())) worker.handle(event);
          return null;
        });
  }

  @Test
  void duplicateCompletionEventsCreateOneDurableProduct() {
    var worker = worker();
    var event = event();
    deliver(worker, event);
    deliver(worker, event);
    deliver(worker, event());
    verifyNoInteractions(http, objects);
    assertEquals(
        1, db.queryForObject("SELECT count(*) FROM simulation_product_work", Integer.class));
    worker.work();
    assertEquals("STORED", worker.read(id.toString()).status());
    worker.work();
    assertEquals(1, store.history("simulation-source-product", id.toString()).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void lostWorkerAndTransientFailureRecoverWithFencedStatus() {
    var worker = worker();
    deliver(worker, event());
    var old = worker.claim().orElseThrow();
    assertTrue(worker.claim().isEmpty());
    db.update("UPDATE simulation_product_work SET lease_until=now()-interval '1 second'");
    var next = worker.claim().orElseThrow();
    worker.finish(old, "STORED", null);
    assertEquals("BUILDING", worker.read(id.toString()).status());
    doThrow(new IllegalStateException("offline"))
        .when(http)
        .get(eq("acquisition"), anyString(), eq(JsonNode.class));
    worker.build(next);
    assertEquals("RETRY", worker.read(id.toString()).status());
    assertTrue(worker.claim().isEmpty());
    doReturn(manifest).when(http).get(eq("acquisition"), anyString(), eq(JsonNode.class));
    db.update("UPDATE simulation_product_work SET next_attempt_at=now()-interval '1 second'");
    worker.work();
    assertEquals("STORED", worker.read(id.toString()).status());
    worker.build(old);
    assertEquals(1, store.history("simulation-source-product", id.toString()).size());
  }

  @Test
  void changedOwnerEvidenceIsRejectedBeforeBytes() {
    var worker = worker();
    deliver(worker, event());
    ((ObjectNode) manifest.path("body")).put("provenance", "changed");
    worker.work();
    assertEquals("REJECTED", worker.read(id.toString()).status());
    verifyNoInteractions(objects);
    verify(http, never()).download(anyString(), anyString(), any(), anyLong());
  }

  @Test
  void invalidEnvelopeRollsBackInboxAndOtherInputsArePreserved() {
    var worker = worker();
    var event = event();
    var wrong =
        new ServiceEvent(
            event.eventId(),
            1,
            event.type(),
            UUID.randomUUID().toString(),
            2,
            event.correlationId(),
            null,
            event.occurredAt(),
            event.payload());
    assertThrows(ApiException.class, () -> deliver(worker, wrong));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    deliver(
        worker,
        new ServiceEvent(
            UUID.randomUUID(),
            1,
            "AcquisitionDataComplete",
            id.toString(),
            2,
            UUID.randomUUID(),
            null,
            MissionInstant.tai(1000),
            manifest));
    assertEquals(1, store.list("deferred-product-input", 10).size());
    assertTrue(worker.claim().isEmpty());
  }
}
