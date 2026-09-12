package msc.services.anomaly;

import java.util.*;
import msc.platform.StateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable competing claims; checks elapsed freshness even if telemetry stops completely. */
@Component
public final class SafetyWatch {
  private final JdbcTemplate db;
  private final StateStore store;
  private final SafetyStore safety;
  private final SafetyEvidence evidence;

  public SafetyWatch(
      JdbcTemplate db, StateStore store, SafetyStore safety, SafetyEvidence evidence) {
    this.db = db;
    this.store = store;
    this.safety = safety;
    this.evidence = evidence;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  public void watch() {
    var claim =
        store.transaction(
            () -> {
              db.update(
                  "INSERT INTO safety_watch(spacecraft_id) SELECT id FROM state_head WHERE"
                      + " kind='safety-policy' ON CONFLICT DO NOTHING");
              var craft =
                  db.query(
                      "SELECT spacecraft_id FROM safety_watch WHERE next_check_at<=now() AND"
                          + " (lease_until IS NULL OR lease_until<now()) ORDER BY next_check_at FOR"
                          + " UPDATE SKIP LOCKED LIMIT 1",
                      (r, n) -> r.getString(1));
              if (craft.isEmpty()) return Optional.<Map.Entry<String, UUID>>empty();
              UUID token = UUID.randomUUID();
              db.update(
                  "UPDATE safety_watch SET lease_token=?,lease_until=now()+interval '30"
                      + " seconds',next_check_at=now()+interval '5 seconds' WHERE spacecraft_id=?",
                  token,
                  craft.getFirst());
              return Optional.of(Map.entry(craft.getFirst(), token));
            });
    claim.ifPresent(
        c -> {
          try {
            Optional<SafetyEvidence.Reading> current;
            try {
              current = Optional.of(evidence.current(c.getKey()));
            } catch (RuntimeException unavailable) {
              current = Optional.empty();
            }
            var reading = current;
            store.transaction(() -> safety.check(c.getKey(), reading));
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(SafetyWatch.class)
                .warn(
                    "Safety watch failed for {} ({})",
                    c.getKey(),
                    failure.getClass().getSimpleName());
          } finally {
            db.update(
                "UPDATE safety_watch SET lease_token=NULL,lease_until=NULL WHERE spacecraft_id=?"
                    + " AND lease_token=?",
                c.getKey(),
                c.getValue());
          }
        });
  }
}
