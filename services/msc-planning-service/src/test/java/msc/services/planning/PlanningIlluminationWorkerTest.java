package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import msc.platform.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlanningIlluminationWorkerTest extends PlanningIlluminationPersistenceTest {
  PlanningIlluminationWorker worker;

  @BeforeEach
  void queue() {
    db.execute("TRUNCATE planning_illumination_work");
    db.update(
        "INSERT INTO planning_work(request_id,revision,last_attempt_id) VALUES(?,?,?)",
        run.requestId(),
        run.requestRevision(),
        run.inputAttemptId());
    db.update(
        "INSERT INTO"
            + " planning_illumination_work(run_id,request_id,request_revision,input_attempt_id)"
            + " VALUES(?,?,?,?)",
        run.id(),
        run.requestId(),
        run.requestRevision(),
        run.inputAttemptId());
    worker = new PlanningIlluminationWorker(db, store, http, api);
  }

  @Test
  void automaticWorkPersistsEvidenceAndCompletesOnce() {
    worker.work();
    assertEquals("EVALUATED", status());
    assertEquals(
        2L,
        db.queryForObject(
            "SELECT assumptions_version FROM planning_illumination_work", Long.class));
    assertEquals(1, store.list("planning-illumination", 10).size());
    clearInvocations(http);
    worker.work();
    verifyNoInteractions(http);
  }

  @Test
  void retryKeepsPinnedVersionAndExpiredWorkerCannotFinishReplacement() {
    var first = worker.claim().orElseThrow();
    assertTrue(worker.claim().isEmpty());
    // Simulate a crash after pinning the owner version, before the FD result is recorded.
    db.update(
        "UPDATE planning_illumination_work SET assumptions_version=2,lease_until=now()-interval '1"
            + " second'");
    var replacement = worker.claim().orElseThrow();
    assertNotEquals(first.token(), replacement.token());
    assertEquals(2L, replacement.assumptionsVersion());
    worker.finish(first, "EVALUATED", null);
    assertEquals("EVALUATING", status());
    worker.evaluate(replacement);
    assertEquals("EVALUATED", status());
    verify(http, never())
        .get(
            eq("mission-definition"),
            eq("/internal/solar-interval-assumptions/" + run.spacecraftId()),
            any());
  }

  @Test
  void supersededInputNeverCallsOwners() {
    db.update("UPDATE planning_work SET last_attempt_id=?", UUID.randomUUID().toString());
    worker.work();
    assertEquals("SUPERSEDED", status());
    verifyNoInteractions(http);
    assertTrue(store.list("planning-illumination", 10).isEmpty());
  }

  @Test
  void unavailableOwnerRetriesWithoutInventingAssumptions() {
    when(http.get(
            eq("mission-definition"),
            anyString(),
            eq(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenThrow(
            new org.springframework.web.client.ResourceAccessException("fixture unavailable"));
    worker.work();
    assertEquals("WAITING_INPUTS", status());
    var state = new PlanningIntake(db, store, json).illuminationStatus(run.id());
    assertNull(state.get("assumptions_version"));
    assertEquals("ILLUMINATION_OWNER_OR_STORAGE_UNAVAILABLE", state.get("last_issue"));
    assertTrue(worker.claim().isEmpty());
    assertTrue(store.list("planning-illumination", 10).isEmpty());
    db.update("UPDATE planning_illumination_work SET next_attempt_at=now()-interval '1 second'");
    assertTrue(worker.claim().isPresent());
  }

  String status() {
    return db.queryForObject(
        "SELECT status FROM planning_illumination_work WHERE run_id=?", String.class, run.id());
  }
}
