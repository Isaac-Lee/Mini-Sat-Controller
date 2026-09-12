package msc.platform;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Inspect delivery backlog without exposing event payloads/secrets. */
@RestController
@RequestMapping("/api/admin/delivery")
public final class OperationsAdmin {
  private final JdbcTemplate db;

  public OperationsAdmin(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/inbox/{eventId}")
  public Map<String, Boolean> received(@PathVariable java.util.UUID eventId) {
    return Map.of(
        "received",
        Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM inbox WHERE event_id=?)", Boolean.class, eventId)));
  }

  @GetMapping
  public Map<String, Object> status() {
    return Map.of(
        "pendingOutbox",
        db.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class),
        "receivedEvents",
        db.queryForObject("SELECT count(*) FROM inbox", Long.class),
        "maxPublishAttempts",
        db.queryForObject(
            "SELECT COALESCE(max(attempts),0) FROM outbox WHERE published_at IS NULL",
            Integer.class));
  }
}
