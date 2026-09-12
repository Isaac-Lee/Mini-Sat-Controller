package msc.services.acquisition;

import java.util.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Inbox only enqueues; bounded owner I/O runs outside transactions and is retryable. */
@RestController
public class SimulationSourceWorker implements EventHandler {
  record Claim(String receiptId, String receiptSha256, UUID token) {}

  public record Work(String receiptId, String status, long attempts, String lastIssue) {}

  private final StateStore store;
  private final JdbcTemplate db;
  private final Json json;
  private final SimulationSourceApi api;

  public SimulationSourceWorker(
      StateStore store, JdbcTemplate db, Json json, SimulationSourceApi api) {
    this.store = store;
    this.db = db;
    this.json = json;
    this.api = api;
  }

  @Override
  public void handle(ServiceEvent event) {
    if (!event.type().equals("SimulatedPayloadReceived")) {
      store.create("deferred-acquisition-input", event.eventId().toString(), event);
      return;
    }
    event.occurredAt().requireTai();
    String id = event.payload().path("id").asText();
    api.validate(new SimulationSourceApi.Import(id), event.payload());
    if (event.aggregateVersion() != event.payload().path("version").asLong())
      throw ApiException.invalid("Receipt event version mismatch");
    String hash = json.fingerprint(event.payload());
    store.lock("simulation-source-work:" + id);
    var prior =
        db.queryForList(
            "SELECT receipt_sha256 FROM simulation_source_work WHERE receipt_id=?",
            String.class,
            id);
    if (!prior.isEmpty()) {
      if (!prior.getFirst().equals(hash)) throw ApiException.conflict("Conflicting receipt event");
      return;
    }
    db.update(
        "INSERT INTO simulation_source_work(receipt_id,receipt_sha256,source_event_id) VALUES"
            + " (?,?,?)",
        id,
        hash,
        event.eventId());
  }

  Optional<Claim> claim() {
    return store.transaction(
        () -> {
          var rows =
              db.query(
                  "SELECT receipt_id,receipt_sha256 FROM simulation_source_work WHERE (status IN"
                      + " ('QUEUED','RETRY') AND next_attempt_at<=now()) OR (status='IMPORTING' AND"
                      + " lease_until<=now()) ORDER BY next_attempt_at,receipt_id FOR UPDATE SKIP"
                      + " LOCKED LIMIT 1",
                  (rs, n) -> new Claim(rs.getString(1), rs.getString(2), UUID.randomUUID()));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          db.update(
              "UPDATE simulation_source_work SET"
                  + " status='IMPORTING',lease_token=?,lease_until=now()+interval '5"
                  + " minutes',attempts=attempts+1 WHERE receipt_id=?",
              claim.token(),
              claim.receiptId());
          return Optional.of(claim);
        });
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  public void work() {
    claim().ifPresent(this::acquire);
  }

  void acquire(Claim claim) {
    try {
      api.acquireForActor(
          new SimulationSourceApi.Import(claim.receiptId()),
          "automatic:" + claim.receiptId(),
          "acquisition-source-worker",
          claim.receiptSha256());
      finish(claim, "STORED", null);
    } catch (ApiException failure) {
      finish(claim, failure.status().is4xxClientError() ? "REJECTED" : "RETRY", failure.code());
    } catch (java.io.IOException | RuntimeException unavailable) {
      finish(claim, "RETRY", "SOURCE_OWNER_OR_STORAGE_UNAVAILABLE");
    }
  }

  void finish(Claim claim, String status, String issue) {
    db.update(
        "UPDATE simulation_source_work SET"
            + " status=?,last_issue=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval"
            + " '30 seconds' WHERE receipt_id=? AND lease_token=? AND status='IMPORTING' AND"
            + " lease_until>now()",
        status,
        issue,
        claim.receiptId(),
        claim.token());
  }

  @GetMapping({
    "/api/acquisition/simulation-sources/{id}/work",
    "/internal/acquisition/simulation-sources/{id}/work"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public Work read(@PathVariable String id) {
    var rows =
        db.query(
            "SELECT receipt_id,status,attempts,last_issue FROM simulation_source_work WHERE"
                + " receipt_id=?",
            (rs, n) -> new Work(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
            id);
    if (rows.isEmpty()) throw ApiException.missing("Acquisition source work not found");
    return rows.getFirst();
  }
}
