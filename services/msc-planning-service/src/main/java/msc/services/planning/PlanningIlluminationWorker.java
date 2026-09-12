package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable owner-pinned evidence work. Completion never grants schedule feasibility. */
@Component
public final class PlanningIlluminationWorker {
  record Claim(String runId, UUID token, Long assumptionsVersion) {}

  private final JdbcTemplate db;
  private final StateStore store;
  private final ServiceHttp http;
  private final PlanningIlluminationApi api;

  public PlanningIlluminationWorker(
      JdbcTemplate db, StateStore store, ServiceHttp http, PlanningIlluminationApi api) {
    this.db = db;
    this.store = store;
    this.http = http;
    this.api = api;
  }

  Optional<Claim> claim() {
    return store.transaction(
        () -> {
          db.update(
              "UPDATE planning_illumination_work w SET"
                  + " status='SUPERSEDED',lease_token=NULL,lease_until=NULL WHERE status IN"
                  + " ('QUEUED','WAITING_INPUTS','EVALUATING') AND NOT EXISTS (SELECT 1 FROM"
                  + " planning_work p WHERE p.request_id=w.request_id AND"
                  + " p.revision=w.request_revision AND p.revision>p.invalidated_through AND"
                  + " p.last_attempt_id=w.input_attempt_id)");
          var rows =
              db.query(
                  "SELECT run_id,assumptions_version FROM planning_illumination_work "
                      + "WHERE (status IN ('QUEUED','WAITING_INPUTS') AND next_attempt_at<=now()) "
                      + "OR (status='EVALUATING' AND lease_until<=now()) "
                      + "ORDER BY next_attempt_at,run_id FOR UPDATE SKIP LOCKED LIMIT 1",
                  (rs, n) ->
                      new Claim(rs.getString(1), UUID.randomUUID(), rs.getObject(2, Long.class)));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          // Up to 32 bounded FD calls; expired workers can only publish immutable evidence.
          db.update(
              "UPDATE planning_illumination_work SET status='EVALUATING',lease_token=?,"
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
      if (claim.assumptionsVersion() != null) version = claim.assumptionsVersion();
      else {
        var run = store.require("planning-run", claim.runId(), PlanningRuns.Published.class).body();
        var source =
            http.get(
                "mission-definition",
                "/internal/solar-interval-assumptions/"
                    + PlanningInputs.segment(run.spacecraftId()),
                JsonNode.class);
        var revision = source.path("version");
        if (!run.spacecraftId().equals(source.path("id").asText())
            || !revision.isIntegralNumber()
            || !revision.canConvertToLong()
            || revision.asLong() <= 0)
          throw ApiException.invalid("Current solar assumptions identity/version mismatch");
        version = revision.asLong();
        if (db.update(
                "UPDATE planning_illumination_work SET assumptions_version=? WHERE run_id=? "
                    + "AND lease_token=? AND status='EVALUATING' AND lease_until>now()",
                version,
                claim.runId(),
                claim.token())
            != 1) return;
      }
      api.evaluateForActor(
          claim.runId(),
          new PlanningIlluminationApi.Evaluate(version),
          "automatic:" + claim.runId() + ":" + version,
          "planning-illumination-worker");
      finish(claim, "EVALUATED", null);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
      finish(claim, "WAITING_INPUTS", "SOLAR_ASSUMPTIONS_UNAVAILABLE");
    } catch (ApiException invalid) {
      finish(
          claim,
          invalid.status().is4xxClientError() ? "REJECTED" : "WAITING_INPUTS",
          invalid.code());
    } catch (RuntimeException unavailable) {
      finish(claim, "WAITING_INPUTS", "ILLUMINATION_OWNER_OR_STORAGE_UNAVAILABLE");
    }
  }

  void finish(Claim claim, String status, String issue) {
    db.update(
        "UPDATE planning_illumination_work SET"
            + " status=?,last_issue=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval"
            + " '30 seconds' WHERE run_id=? AND lease_token=? AND status='EVALUATING' AND"
            + " lease_until>now()",
        status,
        issue,
        claim.runId(),
        claim.token());
  }
}
