package msc.services.product;

import java.util.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Inbox only enqueues; bounded owner I/O runs outside transactions and is retryable. */
@RestController
public class SimulationProductWorker implements EventHandler {
  record Claim(String manifestId, String manifestSha256, UUID token) {}

  public record Work(String manifestId, String status, long attempts, String lastIssue) {}

  private final StateStore store;
  private final JdbcTemplate db;
  private final Json json;
  private final SimulationProductApi api;

  public SimulationProductWorker(
      StateStore store, JdbcTemplate db, Json json, SimulationProductApi api) {
    this.store = store;
    this.db = db;
    this.json = json;
    this.api = api;
  }

  @Override
  public void handle(ServiceEvent event) {
    if (!event.type().equals("SimulationAcquisitionDataComplete")) {
      store.create("deferred-product-input", event.eventId().toString(), event);
      return;
    }
    event.occurredAt().requireTai();
    String id = event.payload().path("id").asText();
    api.validate(new SimulationProductApi.Create(UUID.fromString(id)), event.payload());
    if (!id.equals(event.aggregateId())
        || event.aggregateVersion() != event.payload().path("version").asLong())
      throw ApiException.invalid("Manifest event version mismatch");
    String hash = json.fingerprint(event.payload());
    store.lock("simulation-product-work:" + id);
    var prior =
        db.queryForList(
            "SELECT manifest_sha256 FROM simulation_product_work WHERE manifest_id=?",
            String.class,
            id);
    if (!prior.isEmpty()) {
      if (!prior.getFirst().equals(hash)) throw ApiException.conflict("Conflicting manifest event");
      return;
    }
    db.update(
        "INSERT INTO simulation_product_work(manifest_id,manifest_sha256,source_event_id) VALUES"
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
                  "SELECT manifest_id,manifest_sha256 FROM simulation_product_work WHERE (status IN"
                      + " ('QUEUED','RETRY') AND next_attempt_at<=now()) OR (status='BUILDING' AND"
                      + " lease_until<=now()) ORDER BY next_attempt_at,manifest_id FOR UPDATE SKIP"
                      + " LOCKED LIMIT 1",
                  (rs, n) -> new Claim(rs.getString(1), rs.getString(2), UUID.randomUUID()));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          db.update(
              "UPDATE simulation_product_work SET"
                  + " status='BUILDING',lease_token=?,lease_until=now()+interval '60"
                  + " minutes',attempts=attempts+1 WHERE manifest_id=?",
              claim.token(),
              claim.manifestId());
          return Optional.of(claim);
        });
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  public void work() {
    claim().ifPresent(this::build);
  }

  void build(Claim claim) {
    try {
      api.createForActor(
          new SimulationProductApi.Create(UUID.fromString(claim.manifestId())),
          "automatic:" + claim.manifestId(),
          "product-source-worker",
          claim.manifestSha256());
      finish(claim, "STORED", null);
    } catch (ApiException failure) {
      finish(claim, failure.status().is4xxClientError() ? "REJECTED" : "RETRY", failure.code());
    } catch (java.io.IOException | RuntimeException unavailable) {
      finish(claim, "RETRY", "PRODUCT_OWNER_OR_STORAGE_UNAVAILABLE");
    }
  }

  void finish(Claim claim, String status, String issue) {
    db.update(
        "UPDATE simulation_product_work SET"
            + " status=?,last_issue=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval"
            + " '30 seconds' WHERE manifest_id=? AND lease_token=? AND status='BUILDING' AND"
            + " lease_until>now()",
        status,
        issue,
        claim.manifestId(),
        claim.token());
  }

  @GetMapping({
    "/api/products/simulation-source-packages/{id}/work",
    "/internal/products/simulation-source-packages/{id}/work"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public Work read(@PathVariable String id) {
    var rows =
        db.query(
            "SELECT manifest_id,status,attempts,last_issue FROM simulation_product_work WHERE"
                + " manifest_id=?",
            (rs, n) -> new Work(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
            id);
    if (rows.isEmpty()) throw ApiException.missing("Product work not found");
    return rows.getFirst();
  }
}
