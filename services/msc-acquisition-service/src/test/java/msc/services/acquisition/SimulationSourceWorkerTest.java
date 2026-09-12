package msc.services.acquisition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class SimulationSourceWorkerTest extends SimulationSourceTest {
  SimulationSourceWorker worker() {
    return new SimulationSourceWorker(store, db, json, api);
  }

  ServiceEvent event() {
    return new ServiceEvent(
        UUID.randomUUID(),
        1,
        "SimulatedPayloadReceived",
        "spacecraft",
        1,
        UUID.randomUUID(),
        null,
        MissionInstant.tai(1000),
        receipt);
  }

  void deliver(SimulationSourceWorker worker, ServiceEvent event) {
    store.transaction(
        () -> {
          if (store.receive(event.eventId())) worker.handle(event);
          return null;
        });
  }

  @Test
  void inboxDeduplicatesAndWorkerImportsPinnedReceipt() {
    var worker = worker();
    var event = event();
    deliver(worker, event);
    deliver(worker, event);
    deliver(worker, event());
    assertEquals(
        1, db.queryForObject("SELECT count(*) FROM simulation_source_work", Integer.class));
    verifyNoInteractions(http, objects);
    worker.work();
    assertEquals("STORED", worker.read(id).status());
    assertEquals(1, worker.read(id).attempts());
    assertEquals(1, store.history("simulation-acquisition-source", id).size());
    worker.work();
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void retryAndExpiredLeaseRecoverWithoutDuplicatePublication() {
    var worker = worker();
    deliver(worker, event());
    var old = worker.claim().orElseThrow();
    assertTrue(worker.claim().isEmpty());
    db.update("UPDATE simulation_source_work SET lease_until=now()-interval '1 second'");
    var replacement = worker.claim().orElseThrow();
    worker.finish(old, "STORED", null);
    assertEquals("IMPORTING", worker.read(id).status());
    doThrow(new IllegalStateException("offline"))
        .when(http)
        .get(eq("simulator"), anyString(), eq(com.fasterxml.jackson.databind.JsonNode.class));
    worker.acquire(replacement);
    assertEquals("RETRY", worker.read(id).status());
    assertTrue(worker.claim().isEmpty());
    doReturn(receipt)
        .when(http)
        .get(eq("simulator"), anyString(), eq(com.fasterxml.jackson.databind.JsonNode.class));
    db.update("UPDATE simulation_source_work SET next_attempt_at=now()-interval '1 second'");
    worker.work();
    assertEquals("STORED", worker.read(id).status());
    worker.acquire(old);
    assertEquals(1, store.history("simulation-acquisition-source", id).size());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void conflictingEventsRollbackInboxAndOwnerMismatchRejectsBeforeBytes() {
    var worker = worker();
    deliver(worker, event());
    ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.path("body"))
        .put("stationId", "changed");
    assertThrows(ApiException.class, () -> deliver(worker, event()));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    worker.work();
    assertEquals("REJECTED", worker.read(id).status());
    verify(http, never()).download(anyString(), anyString(), any(), anyLong());
    verifyNoInteractions(objects);
  }

  @Test
  void priorImportAndReplayMustMatchPinnedEventHash() throws Exception {
    api.acquire(request, "manual", actor);
    assertThrows(
        ApiException.class,
        () -> api.acquireForActor(request, "automatic", "worker", "f".repeat(64)));
    String hash = json.fingerprint(receipt);
    api.acquireForActor(request, "automatic", "worker", hash);
    clearInvocations(http, objects);
    assertThrows(
        ApiException.class,
        () -> api.acquireForActor(request, "automatic", "worker", "f".repeat(64)));
    verifyNoInteractions(http, objects);
  }

  @Test
  void otherInputsAreRetainedForPendingOperationalFlows() {
    var worker = worker();
    var event =
        new ServiceEvent(
            UUID.randomUUID(),
            1,
            "ScheduleVersionCommitted",
            "spacecraft",
            1,
            UUID.randomUUID(),
            null,
            MissionInstant.tai(1000),
            receipt);
    deliver(worker, event);
    assertEquals(1, store.list("deferred-acquisition-input", 10).size());
    assertTrue(worker.claim().isEmpty());
    verifyNoInteractions(http, objects);
  }
}
