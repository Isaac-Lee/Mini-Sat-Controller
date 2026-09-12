package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.TaskingContracts.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class PlanningIntake {
  public record Claim(
      String requestId,
      long revision,
      UUID token,
      long epoch,
      AcceptedRequest accepted,
      long attemptNumber) {
    public Claim(
        String requestId, long revision, UUID token, long epoch, AcceptedRequest accepted) {
      this(requestId, revision, token, epoch, accepted, 0);
    }
  }

  private final JdbcTemplate db;
  private final StateStore store;
  private final Json json;

  public PlanningIntake(JdbcTemplate db, StateStore store, Json json) {
    this.db = db;
    this.store = store;
    this.json = json;
  }

  public void accepted(AcceptedRequest request, ServiceEvent event) {
    UUID.fromString(request.requestId());
    if (request.revision() < 1
        || request.area() == null
        || request.criteria() == null
        || request.deadline() == null
        || request.priority() < 0
        || request.priority() > 100)
      throw ApiException.invalid("Invalid accepted request contract");
    store.lock("planning-work:" + request.requestId());
    db.update(
        "INSERT INTO planning_work(request_id) VALUES(?) ON CONFLICT DO NOTHING",
        request.requestId());
    var prior =
        db.query(
                "SELECT revision,invalidated_through,accepted::text FROM planning_work WHERE"
                    + " request_id=?",
                (r, n) -> new Object[] {r.getLong(1), r.getLong(2), r.getString(3)},
                request.requestId())
            .getFirst();
    if ((long) prior[0] == request.revision()
        && request.revision() > (long) prior[1]
        && prior[2] != null
        && !json.fingerprint(json.read((String) prior[2], JsonNode.class))
            .equals(json.fingerprint(request)))
      throw ApiException.conflict("Accepted revision has contradictory content");
    int changed =
        db.update(
            "UPDATE planning_work SET"
                + " revision=?,priority=?,status='QUEUED',accepted=?::jsonb,lease_token=NULL,lease_until=NULL,next_attempt_at=now(),last_attempt_id=NULL,captured_epoch=-1"
                + " WHERE request_id=? AND revision<? AND invalidated_through<?",
            request.revision(),
            request.priority(),
            json.write(request),
            request.requestId(),
            request.revision(),
            request.revision());
    if (changed == 1)
      store.event(
          "PlanningRequestQueued",
          request.requestId(),
          request.revision(),
          event.correlationId(),
          event.eventId(),
          request);
  }

  public void invalidate(RequestProgress request, ServiceEvent event) {
    UUID.fromString(request.requestId());
    store.lock("planning-work:" + request.requestId());
    db.update(
        "INSERT INTO planning_work(request_id,invalidated_through) VALUES(?,?) ON"
            + " CONFLICT(request_id) DO UPDATE SET"
            + " invalidated_through=greatest(planning_work.invalidated_through,excluded.invalidated_through)",
        request.requestId(),
        request.revision());
    db.update(
        "UPDATE planning_work SET status='INVALIDATED',lease_token=NULL,lease_until=NULL WHERE"
            + " request_id=? AND revision<=invalidated_through",
        request.requestId());
  }

  public void changedInputs() {
    db.update("UPDATE planning_inputs_epoch SET epoch=epoch+1 WHERE id=1");
  }

  public Optional<Claim> claim() {
    return store.transaction(
        () -> {
          var rows =
              db.query(
                  "SELECT w.request_id,w.revision,w.accepted::text,e.epoch,w.attempts FROM"
                      + " planning_work w CROSS JOIN planning_inputs_epoch e WHERE w.status IN"
                      + " ('QUEUED','COLLECTING_INPUTS','WAITING_INPUTS','REQUEST_UNAVAILABLE') AND"
                      + " (w.lease_until IS NULL OR w.lease_until<now()) AND"
                      + " (w.status='COLLECTING_INPUTS' OR w.next_attempt_at<=now() OR"
                      + " (w.captured_epoch<e.epoch AND w.last_started_at<now()-interval '5"
                      + " seconds')) ORDER BY w.priority DESC,w.created_at FOR UPDATE OF w SKIP"
                      + " LOCKED LIMIT 1",
                  (r, n) ->
                      new Claim(
                          r.getString(1),
                          r.getLong(2),
                          UUID.randomUUID(),
                          r.getLong(4),
                          json.read(r.getString(3), AcceptedRequest.class),
                          r.getLong(5)));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          db.update(
              "UPDATE planning_work SET"
                  + " status='COLLECTING_INPUTS',lease_token=?,lease_until=now()+interval '120"
                  + " seconds',last_started_at=now(),attempts=attempts+1 WHERE request_id=?",
              claim.token(),
              claim.requestId());
          return Optional.of(claim);
        });
  }

  public Map<String, Object> illuminationStatus(String runId) {
    var rows =
        db.queryForList(
            "SELECT run_id,status,assumptions_version,attempts,last_issue FROM"
                + " planning_illumination_work WHERE run_id=?",
            runId);
    if (rows.isEmpty()) throw ApiException.missing("Automatic illumination work not found");
    return rows.getFirst();
  }

  public Map<String, Object> cameraStatus(String runId) {
    var rows =
        db.queryForList(
            "SELECT run_id,status,camera_model_version,attempts,last_issue FROM"
                + " planning_camera_work WHERE run_id=?",
            runId);
    if (rows.isEmpty()) throw ApiException.missing("Automatic camera work not found");
    return rows.getFirst();
  }

  public void finish(Claim claim, PlanningInputs.Attempt attempt) {
    if (!claim.requestId().equals(attempt.requestId())
        || claim.revision() != attempt.revision()
        || !Set.of("WAITING_INPUTS", "REQUEST_UNAVAILABLE", "TERMINAL", "INVALIDATED")
            .contains(attempt.status()))
      throw ApiException.invalid("Input attempt does not match claimed request");
    store.transaction(
        () -> {
          // Lock the same row before testing the lease; cancellation/revision updates fence
          // completion.
          var rows =
              db.queryForList(
                  "SELECT request_id FROM planning_work WHERE request_id=? AND revision=? AND"
                      + " lease_token=? AND lease_until>now() AND revision>invalidated_through FOR"
                      + " UPDATE",
                  String.class,
                  claim.requestId(),
                  claim.revision(),
                  claim.token());
          if (rows.isEmpty()) return null;
          for (var asset :
              attempt.assets().stream()
                  .sorted(Comparator.comparing(PlanningInputs.Asset::spacecraftId))
                  .toList()) {
            asset
                .pointGeometry()
                .ifPresent(
                    evidence -> {
                      String searchKey = evidence.value().path("searchKey").asText();
                      if (searchKey.isBlank())
                        throw ApiException.invalid("Geometry search key required");
                      store.lock("planning-geometry:" + searchKey);
                      if (store
                          .find("planning-geometry", searchKey, PlanningInputs.Evidence.class)
                          .isEmpty()) store.create("planning-geometry", searchKey, evidence);
                    });
          }
          store.create("planning-input-attempt", attempt.id(), attempt);
          var recorded = new PlanningRuns(store, json).publish(attempt);
          var resources = new PlanningResources(json);
          var schedules = new JdbcScheduleRepository(store, json, db);
          for (var run :
              recorded.stream()
                  .sorted(Comparator.comparing(PlanningRuns.Published::spacecraftId))
                  .toList()) {
            var asset =
                attempt.assets().stream()
                    .filter(a -> a.spacecraftId().equals(run.spacecraftId()))
                    .findFirst()
                    .orElseThrow();
            resources.publish(store, schedules, attempt, asset, run);
            db.update(
                "INSERT INTO"
                    + " planning_illumination_work(run_id,request_id,request_revision,input_attempt_id)"
                    + " VALUES(?,?,?,?)",
                run.id(),
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
          }
          db.update(
              "UPDATE planning_work SET"
                  + " status=?,last_attempt_id=?,captured_epoch=?,next_attempt_at=now()+interval"
                  + " '30 seconds',lease_token=NULL,lease_until=NULL,invalidated_through=CASE WHEN"
                  + " ? THEN greatest(invalidated_through,revision) ELSE invalidated_through END"
                  + " WHERE request_id=?",
              attempt.status(),
              attempt.id(),
              claim.epoch(),
              Set.of("TERMINAL", "INVALIDATED").contains(attempt.status()),
              claim.requestId());
          store.event(
              "PlanningInputsExamined",
              claim.requestId(),
              claim.revision(),
              UUID.randomUUID(),
              null,
              Map.of(
                  "requestId",
                  claim.requestId(),
                  "revision",
                  claim.revision(),
                  "attemptId",
                  attempt.id(),
                  "status",
                  attempt.status()));
          return null;
        });
  }

  public Map<String, Object> status(String id) {
    var rows =
        db.queryForList(
            "SELECT request_id,revision,status,attempts,last_attempt_id,next_attempt_at FROM"
                + " planning_work WHERE request_id=?",
            id);
    if (rows.isEmpty()) throw ApiException.missing("Planning request not ingested yet");
    return rows.getFirst();
  }
}
