package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable owner-pinned evidence work. Completion never grants schedule feasibility. */
@Component
public final class PlanningCameraWorker {
  record Claim(String runId, UUID token, Long cameraModelVersion) {}

  private final JdbcTemplate db;
  private final StateStore store;
  private final ServiceHttp http;
  private final PlanningCameraApi api;

  public PlanningCameraWorker(
      JdbcTemplate db, StateStore store, ServiceHttp http, PlanningCameraApi api) {
    this.db = db;
    this.store = store;
    this.http = http;
    this.api = api;
  }

  Optional<Claim> claim() {
    return store.transaction(
        () -> {
          // Recover runs published by an older replica during a rolling upgrade. Both work
          // queues identify the same run publication; only the still-current attempt is eligible.
          db.update(
              "INSERT INTO"
                  + " planning_camera_work(run_id,request_id,request_revision,input_attempt_id)"
                  + " SELECT i.run_id,i.request_id,i.request_revision,i.input_attempt_id FROM"
                  + " planning_illumination_work i JOIN planning_work p ON"
                  + " p.request_id=i.request_id AND p.revision=i.request_revision AND"
                  + " p.last_attempt_id=i.input_attempt_id AND p.revision>p.invalidated_through"
                  + " WHERE NOT EXISTS (SELECT 1 FROM planning_camera_work c WHERE"
                  + " c.run_id=i.run_id) ORDER BY i.run_id LIMIT 32 ON CONFLICT DO NOTHING");
          db.update(
              "UPDATE planning_camera_work w SET"
                  + " status='SUPERSEDED',lease_token=NULL,lease_until=NULL WHERE status IN"
                  + " ('QUEUED','WAITING_INPUTS','EVALUATING') AND NOT EXISTS (SELECT 1 FROM"
                  + " planning_work p WHERE p.request_id=w.request_id AND"
                  + " p.revision=w.request_revision AND p.revision>p.invalidated_through AND"
                  + " p.last_attempt_id=w.input_attempt_id)");
          var rows =
              db.query(
                  "SELECT run_id,camera_model_version FROM planning_camera_work "
                      + "WHERE (status IN ('QUEUED','WAITING_INPUTS') AND next_attempt_at<=now()) "
                      + "OR (status='EVALUATING' AND lease_until<=now()) "
                      + "ORDER BY next_attempt_at,run_id FOR UPDATE SKIP LOCKED LIMIT 1",
                  (rs, n) ->
                      new Claim(rs.getString(1), UUID.randomUUID(), rs.getObject(2, Long.class)));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          // Up to 32 bounded FD calls; expired workers can only publish immutable evidence.
          db.update(
              "UPDATE planning_camera_work SET status='EVALUATING',lease_token=?,"
                  + "lease_until=now()+interval '40 minutes',attempts=attempts+1 WHERE run_id=?",
              claim.token(),
              claim.runId());
          return Optional.of(claim);
        });
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  public void work() {
    claim().ifPresent(this::evaluate);
  }

  void evaluate(Claim claim) {
    try {
      long version;
      if (claim.cameraModelVersion() != null) version = claim.cameraModelVersion();
      else {
        var run = store.require("planning-run", claim.runId(), PlanningRuns.Published.class).body();
        var source =
            http.get(
                "mission-definition",
                "/internal/simulation-camera-models/" + PlanningInputs.segment(run.spacecraftId()),
                JsonNode.class);
        var revision = source.path("version");
        if (!run.spacecraftId().equals(source.path("id").asText())
            || !revision.isIntegralNumber()
            || !revision.canConvertToLong()
            || revision.asLong() <= 0)
          throw ApiException.invalid("Current camera model identity/version mismatch");
        var camera = source.path("body");
        var planning = run.simulationModel().value();
        if (!camera.path("simulationPlanningModelVersion").isIntegralNumber()
            || !camera.path("simulationPlanningModelVersion").canConvertToLong()
            || camera.path("simulationPlanningModelVersion").asLong()
                != planning.path("version").asLong()
            || !camera
                .path("missionDefinitionVersion")
                .equals(planning.path("body").path("missionDefinitionVersion"))) {
          finish(claim, "WAITING_INPUTS", "CAMERA_MODEL_FOR_PINNED_RUN_UNAVAILABLE");
          return;
        }
        version = revision.asLong();
        if (db.update(
                "UPDATE planning_camera_work SET camera_model_version=? WHERE run_id=? "
                    + "AND lease_token=? AND status='EVALUATING' AND lease_until>now()",
                version,
                claim.runId(),
                claim.token())
            != 1) return;
      }
      api.evaluateForActor(
          claim.runId(),
          new PlanningCameraApi.Evaluate(version),
          "automatic:" + claim.runId() + ":" + version,
          "planning-camera-worker");
      finish(claim, "EVALUATED", null);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
      finish(claim, "WAITING_INPUTS", "CAMERA_MODEL_UNAVAILABLE");
    } catch (ApiException invalid) {
      finish(
          claim,
          invalid.status().is4xxClientError() ? "REJECTED" : "WAITING_INPUTS",
          invalid.code());
    } catch (RuntimeException unavailable) {
      finish(claim, "WAITING_INPUTS", "CAMERA_OWNER_OR_STORAGE_UNAVAILABLE");
    }
  }

  void finish(Claim claim, String status, String issue) {
    db.update(
        "UPDATE planning_camera_work SET"
            + " status=?,last_issue=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval"
            + " '30 seconds' WHERE run_id=? AND lease_token=? AND status='EVALUATING' AND"
            + " lease_until>now()",
        status,
        issue,
        claim.runId(),
        claim.token());
  }
}
