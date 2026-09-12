package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import msc.platform.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlanningCameraWorkerTest extends PlanningCameraPersistenceTest {
  PlanningCameraWorker worker;

  @BeforeEach
  void queue() {
    db.execute("TRUNCATE planning_camera_work");
    db.update(
        "INSERT INTO planning_work(request_id,revision,last_attempt_id) VALUES(?,?,?)",
        run.requestId(),
        run.requestRevision(),
        run.inputAttemptId());
    db.update(
        "INSERT INTO"
            + " planning_camera_work(run_id,request_id,request_revision,input_attempt_id)"
            + " VALUES(?,?,?,?)",
        run.id(),
        run.requestId(),
        run.requestRevision(),
        run.inputAttemptId());
    worker = new PlanningCameraWorker(db, store, http, api);
  }

  @Test
  void automaticWorkPersistsEvidenceAndCompletesOnce() {
    worker.work();
    assertEquals("EVALUATED", status());
    assertEquals(
        1L, db.queryForObject("SELECT camera_model_version FROM planning_camera_work", Long.class));
    assertEquals(1, store.list("planning-camera", 10).size());
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
        "UPDATE planning_camera_work SET camera_model_version=1,lease_until=now()-interval '1"
            + " second'");
    var replacement = worker.claim().orElseThrow();
    assertNotEquals(first.token(), replacement.token());
    assertEquals(1L, replacement.cameraModelVersion());
    worker.finish(first, "EVALUATED", null);
    assertEquals("EVALUATING", status());
    worker.evaluate(replacement);
    assertEquals("EVALUATED", status());
    verify(http, never())
        .get(
            eq("mission-definition"),
            eq("/internal/simulation-camera-models/" + run.spacecraftId()),
            any());
  }

  @Test
  void supersededInputNeverCallsOwners() {
    db.update("UPDATE planning_work SET last_attempt_id=?", UUID.randomUUID().toString());
    worker.work();
    assertEquals("SUPERSEDED", status());
    verifyNoInteractions(http);
    assertTrue(store.list("planning-camera", 10).isEmpty());
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
    var state = new PlanningIntake(db, store, json).cameraStatus(run.id());
    assertNull(state.get("camera_model_version"));
    assertEquals("CAMERA_OWNER_OR_STORAGE_UNAVAILABLE", state.get("last_issue"));
    assertTrue(worker.claim().isEmpty());
    assertTrue(store.list("planning-camera", 10).isEmpty());
    db.update("UPDATE planning_camera_work SET next_attempt_at=now()-interval '1 second'");
    assertTrue(worker.claim().isPresent());
  }

  @Test
  void mismatchedCurrentCameraWaitsWithoutPinningUntilCompatibleVersionExists() {
    when(http.get(
            eq("mission-definition"),
            anyString(),
            eq(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenReturn(fixtures.source(run, 3));
    worker.work();
    assertEquals("WAITING_INPUTS", status());
    assertNull(
        new PlanningIntake(db, store, json).cameraStatus(run.id()).get("camera_model_version"));
    assertTrue(store.list("planning-camera", 10).isEmpty());
    when(http.get(
            eq("mission-definition"),
            anyString(),
            eq(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenReturn(fixtures.source(run, 2));
    db.update("UPDATE planning_camera_work SET next_attempt_at=now()-interval '1 second'");
    worker.work();
    assertEquals("EVALUATED", status());
  }

  @Test
  void recoversCurrentRunPublishedByOlderReplicaWithoutDuplicatingWork() {
    db.update("DELETE FROM planning_camera_work WHERE run_id=?", run.id());
    db.update(
        "INSERT INTO"
            + " planning_illumination_work(run_id,request_id,request_revision,input_attempt_id)"
            + " VALUES(?,?,?,?)",
        run.id(),
        run.requestId(),
        run.requestRevision(),
        run.inputAttemptId());
    worker.work();
    assertEquals("EVALUATED", status());
    worker.work();
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM planning_camera_work WHERE run_id=?", Integer.class, run.id()));
  }

  String status() {
    return db.queryForObject(
        "SELECT status FROM planning_camera_work WHERE run_id=?", String.class, run.id());
  }
}
