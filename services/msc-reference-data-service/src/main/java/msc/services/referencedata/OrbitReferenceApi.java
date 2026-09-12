package msc.services.referencedata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import msc.contracts.OrbitReferenceContracts.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrbitReferenceApi {
  private final JdbcTemplate db;
  private final StateStore store;
  private final CelestrakClient provider;
  private final msc.ports.Clock clock;

  public OrbitReferenceApi(
      JdbcTemplate db, StateStore store, CelestrakClient provider, msc.ports.Clock clock) {
    this.db = db;
    this.store = store;
    this.provider = provider;
    this.clock = clock;
  }

  @GetMapping("/api/tracked-satellites")
  public List<Map<String, Object>> satellites() {
    return db.queryForList(
        "SELECT"
            + " norad_id,display_name,enabled,next_attempt_at,last_snapshot_id,last_error,fetched_at"
            + " FROM orbit_collection ORDER BY norad_id");
  }

  @PutMapping("/api/tracked-satellites/{noradId}")
  @PreAuthorize("hasRole('ADMIN')")
  public TrackedSatellite register(@PathVariable int noradId, @RequestBody TrackedSatellite body) {
    if (noradId != body.noradId()) throw ApiException.invalid("NORAD ID mismatch");
    db.update(
        "INSERT INTO orbit_collection(norad_id,display_name,enabled) VALUES(?,?,?) ON"
            + " CONFLICT(norad_id) DO UPDATE SET"
            + " display_name=excluded.display_name,enabled=excluded.enabled",
        noradId,
        body.displayName(),
        body.enabled());
    return body;
  }

  @PostMapping("/api/tracked-satellites/{noradId}/resume")
  @PreAuthorize("hasRole('ADMIN')")
  public void resume(@PathVariable int noradId) {
    if (db.update(
            "UPDATE orbit_collection SET last_error=NULL,enabled=true WHERE norad_id=?", noradId)
        != 1) throw ApiException.missing("Satellite not registered");
  }

  @GetMapping("/api/orbit-provider")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> providerStatus() {
    return db.queryForMap(
        "SELECT provider,last_error,lease_until FROM orbit_provider_control WHERE"
            + " provider='CelesTrak'");
  }

  @PostMapping("/api/orbit-provider/resume")
  @PreAuthorize("hasRole('ADMIN')")
  public void resumeProvider() {
    db.update("UPDATE orbit_provider_control SET last_error=NULL WHERE provider='CelesTrak'");
  }

  @GetMapping({"/api/orbit-references/{id}", "/internal/orbit-references/{id}"})
  public Snapshot snapshot(@PathVariable String id) {
    return store.require("public-orbit", id, Snapshot.class).body();
  }

  @GetMapping({
    "/api/tracked-satellites/{noradId}/orbit",
    "/internal/tracked-satellites/{noradId}/orbit"
  })
  public Snapshot latest(@PathVariable int noradId) {
    var ids =
        db.query(
            "SELECT last_snapshot_id FROM orbit_collection WHERE norad_id=? AND last_snapshot_id IS"
                + " NOT NULL",
            (r, n) -> r.getString(1),
            noradId);
    if (ids.isEmpty()) throw ApiException.missing("No public orbit snapshot collected");
    return snapshot(ids.getFirst());
  }

  // Durable cooldown is shared by replicas and applies to both scheduled and manual collection.
  @PostMapping("/api/tracked-satellites/{noradId}/refresh")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> refresh(@PathVariable int noradId) {
    var claim = claim(noradId);
    if (claim.isEmpty())
      throw ApiException.conflict(
          "Collection disabled, paused, already running, or inside two-hour cooldown");
    collect(claim.get());
    return db.queryForMap(
        "SELECT norad_id,last_snapshot_id,last_error,next_attempt_at FROM orbit_collection WHERE"
            + " norad_id=?",
        noradId);
  }

  record Claim(int noradId, UUID token) {}

  Optional<Claim> claim(Integer noradId) {
    return store.transaction(
        () -> {
          var providerRows =
              db.queryForList(
                  "SELECT provider FROM orbit_provider_control WHERE provider='CelesTrak' AND"
                      + " last_error IS NULL AND (lease_until IS NULL OR lease_until<now()) FOR"
                      + " UPDATE SKIP LOCKED");
          if (providerRows.isEmpty()) return Optional.empty();
          var found =
              db.query(
                  "SELECT norad_id FROM orbit_collection WHERE enabled AND last_error IS NULL AND"
                      + " next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now())"
                      + (noradId == null ? "" : " AND norad_id=" + noradId)
                      + " ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1",
                  (r, n) -> r.getInt(1));
          if (found.isEmpty()) return Optional.empty();
          var token = UUID.randomUUID();
          db.update(
              "UPDATE orbit_collection SET lease_token=?,lease_until=now()+interval '60"
                  + " seconds',next_attempt_at=now()+interval '2 hours' WHERE norad_id=?",
              token,
              found.getFirst());
          db.update(
              "UPDATE orbit_provider_control SET lease_token=?,lease_until=now()+interval '60"
                  + " seconds' WHERE provider='CelesTrak'",
              token);
          return Optional.of(new Claim(found.getFirst(), token));
        });
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void scheduled() {
    claim(null).ifPresent(this::collect);
  }

  void collect(Claim claim) {
    try {
      String raw = provider.fetch(claim.noradId());
      var elements = provider.parse(claim.noradId(), raw);
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(raw.getBytes(StandardCharsets.UTF_8)));
      var record =
          new Snapshot(
              "gp-" + claim.noradId() + "-" + hash,
              elements,
              "CelesTrak-GP",
              CelestrakClient.url(claim.noradId()),
              clock.now(),
              hash,
              raw);
      store.transaction(
          () -> {
            var current =
                db.query(
                    "SELECT last_snapshot_id FROM orbit_collection WHERE norad_id=? AND"
                        + " lease_token=? AND lease_until>now() FOR UPDATE",
                    (r, n) -> Optional.ofNullable(r.getString(1)),
                    claim.noradId(),
                    claim.token());
            if (current.isEmpty()) return null;
            if (store.find("public-orbit", record.id(), Snapshot.class).isEmpty()) {
              var saved = store.create("public-orbit", record.id(), record);
              store.event(
                  "PublicOrbitReferenceCollected",
                  saved.id(),
                  saved.version(),
                  UUID.randomUUID(),
                  null,
                  record);
            }
            boolean newer =
                current.getFirst().isEmpty()
                    || java.time.LocalDateTime.parse(elements.epochUtc().replace("Z", ""))
                            .compareTo(
                                java.time.LocalDateTime.parse(
                                    snapshot(current.getFirst().get())
                                        .elements()
                                        .epochUtc()
                                        .replace("Z", "")))
                        >= 0;
            db.update(
                "UPDATE orbit_collection SET last_snapshot_id=CASE WHEN ? THEN ? ELSE"
                    + " last_snapshot_id END,fetched_at=now(),lease_token=NULL,lease_until=NULL"
                    + " WHERE norad_id=?",
                newer,
                record.id(),
                claim.noradId());
            db.update(
                "UPDATE orbit_provider_control SET lease_token=NULL,lease_until=NULL WHERE"
                    + " provider='CelesTrak' AND lease_token=?",
                claim.token());
            return null;
          });
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      db.update(
          "UPDATE orbit_collection SET last_error=?,lease_token=NULL,lease_until=NULL WHERE"
              + " norad_id=? AND lease_token=?",
          (error.getClass().getSimpleName() + ": " + error.getMessage())
              .substring(
                  0,
                  Math.min(
                      500,
                      (error.getClass().getSimpleName() + ": " + error.getMessage()).length())),
          claim.noradId(),
          claim.token());
      db.update(
          "UPDATE orbit_provider_control SET last_error=CASE WHEN ? THEN ? ELSE last_error"
              + " END,lease_token=NULL,lease_until=NULL WHERE provider='CelesTrak' AND"
              + " lease_token=?",
          error instanceof java.io.IOException || error instanceof InterruptedException,
          "Provider transport failed; review per-satellite error before resuming",
          claim.token());
    }
  }
}
