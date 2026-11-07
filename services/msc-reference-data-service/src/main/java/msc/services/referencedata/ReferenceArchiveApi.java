package msc.services.referencedata;

import java.util.*;
import msc.contracts.ReferenceArchiveContracts.*;
import msc.domain.referencedata.*;
import msc.domain.shared.Ids.SnapshotId;
import msc.domain.time.TimeWindow;
import msc.platform.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Periodic orekit-data UTC/EOP collection. A snapshot becomes current only after full validation
 * and only if it is not older (by latest measured EOP) than the current one.
 */
@RestController
public class ReferenceArchiveApi {
  static final String KIND = "reference-snapshot";
  static final String SOURCE = "orekit-data";
  private final JdbcTemplate db;
  private final StateStore store;
  private final OrekitDataClient provider;
  private final ReferenceArchive archive;
  private final msc.ports.Clock clock;

  public ReferenceArchiveApi(
      JdbcTemplate db,
      StateStore store,
      OrekitDataClient provider,
      ReferenceArchive archive,
      msc.ports.Clock clock) {
    this.db = db;
    this.store = store;
    this.provider = provider;
    this.archive = archive;
    this.clock = clock;
  }

  @GetMapping({"/api/reference-snapshots/current", "/internal/reference-snapshots/current"})
  public ArchiveStatus status() {
    var row =
        db.queryForMap(
            "SELECT enabled,paused,current_snapshot_id,last_error,last_checked_at::text AS checked"
                + " FROM reference_archive_control WHERE source=?",
            SOURCE);
    var now = clock.now();
    var current =
        Optional.ofNullable((String) row.get("current_snapshot_id")).map(this::snapshot);
    return new ArchiveStatus(
        current.orElse(null),
        current.map(s -> ReferenceArchive.freshness(now, s)).orElse(Freshness.EXPIRED),
        current
            .map(s -> ReferenceArchive.warning(now, s))
            .orElse("No reference snapshot collected"),
        now,
        (Boolean) row.get("enabled") && !(Boolean) row.get("paused"),
        (String) row.get("last_error"),
        (String) row.get("checked"));
  }

  @GetMapping({"/api/reference-snapshots/{id}", "/internal/reference-snapshots/{id}"})
  public ArchiveSnapshot snapshot(@PathVariable String id) {
    return store.require(KIND, id, ArchiveSnapshot.class).body();
  }

  /** Enables collection, or resumes it after an invalid download was reviewed. */
  @PostMapping("/api/reference-archive/resume")
  @PreAuthorize("hasRole('ADMIN')")
  public void resume() {
    db.update(
        "UPDATE reference_archive_control SET enabled=true,paused=false,last_error=NULL,"
            + "next_attempt_at=now() WHERE source=?",
        SOURCE);
  }

  @PostMapping("/api/reference-archive/refresh")
  @PreAuthorize("hasRole('ADMIN')")
  public ArchiveStatus refresh() {
    var token = claim(true).orElseThrow(
        () -> ApiException.conflict("Collection disabled, paused for review, or already running"));
    collect(token);
    return status();
  }

  @Scheduled(fixedDelay = 300000, initialDelay = 60000)
  public void scheduled() {
    claim(false).ifPresent(this::collect);
    assessFreshness();
  }

  // Six-hour cooldown; orekit-data publishes roughly monthly. The 20-minute lease covers five
  // sequential 150 s-bounded requests plus validation.
  Optional<UUID> claim(boolean ignoreCooldown) {
    var token = UUID.randomUUID();
    return db.update(
                "UPDATE reference_archive_control SET lease_token=?,lease_until=now()+interval '20"
                    + " minutes',next_attempt_at=now()+interval '6 hours' WHERE source=? AND"
                    + " enabled AND NOT paused AND (lease_until IS NULL OR lease_until<now())"
                    + (ignoreCooldown ? "" : " AND next_attempt_at<=now()"),
                token,
                SOURCE)
            == 1
        ? Optional.of(token)
        : Optional.empty();
  }

  void collect(UUID token) {
    try {
      String commit = provider.latestCommit();
      String id = "orekit-data-" + commit;
      var known = store.find(KIND, id, ArchiveSnapshot.class);
      // Re-create the deterministic archive if the volume lost or altered it.
      var record =
          known.isPresent() && intact(known.get().body()) ? known.get().body() : download(commit, id);
      store.transaction(
          () -> {
            var current =
                db.query(
                    "SELECT current_snapshot_id FROM reference_archive_control WHERE source=? AND"
                        + " lease_token=? AND lease_until>now() FOR UPDATE",
                    (r, n) -> Optional.ofNullable(r.getString(1)),
                    SOURCE,
                    token);
            if (current.isEmpty()) {
              LoggerFactory.getLogger(ReferenceArchiveApi.class)
                  .warn("Reference archive lease lost; {} not published", id);
              return null;
            }
            if (store.find(KIND, id, ArchiveSnapshot.class).isEmpty()) {
              var saved = store.create(KIND, id, record);
              store.event(
                  "ReferenceSnapshotPublished", id, saved.version(), UUID.randomUUID(), null,
                  record);
            }
            boolean newer =
                current.getFirst().isEmpty()
                    || record
                            .latestMeasured()
                            .compareTo(snapshot(current.getFirst().get()).latestMeasured())
                        >= 0;
            db.update(
                "UPDATE reference_archive_control SET current_snapshot_id=CASE WHEN ? THEN ? ELSE"
                    + " current_snapshot_id END,last_checked_at=now(),last_error=NULL,"
                    + "lease_token=NULL,lease_until=NULL WHERE source=?",
                newer,
                id,
                SOURCE);
            return null;
          });
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      boolean transport =
          error instanceof java.io.IOException
              || error instanceof InterruptedException
              || error instanceof org.springframework.dao.DataAccessException;
      String message = error.getClass().getSimpleName() + ": " + error.getMessage();
      // Transport failures retry after an hour; invalid content waits for operator review.
      db.update(
          "UPDATE reference_archive_control SET last_error=?,paused=NOT ?,last_checked_at=now(),"
              + "next_attempt_at=now()+interval '1 hour',lease_token=NULL,lease_until=NULL"
              + " WHERE source=? AND lease_token=?",
          message.substring(0, Math.min(500, message.length())),
          transport,
          SOURCE,
          token);
    }
  }

  static boolean intact(ArchiveSnapshot snapshot) {
    try {
      var path = java.nio.file.Path.of(snapshot.archivePath());
      return java.nio.file.Files.isRegularFile(path)
          && ReferenceArchive.sha256(java.nio.file.Files.readAllBytes(path))
              .equals(snapshot.archiveSha256());
    } catch (java.io.IOException error) {
      return false;
    }
  }

  private ArchiveSnapshot download(String commit, String id) throws Exception {
    var downloads = new ArrayList<OrekitDataClient.Download>();
    for (String path : ReferenceArchive.FILES) downloads.add(provider.download(commit, path));
    var capturedAt = clock.now();
    var valid = archive.validate(commit, downloads, capturedAt);
    var reference =
        new ReferenceDataSnapshot(
            new SnapshotRef(new SnapshotId(id), 1, SOURCE + "@" + commit, capturedAt),
            "orekit-utc-eop",
            new TimeWindow(valid.eopStart(), valid.expiresAt()),
            KIND + "/" + id);
    return new ArchiveSnapshot(
        reference,
        commit,
        valid.archive().toString(),
        valid.sha256(),
        valid.files(),
        valid.eopStart(),
        valid.eopEnd(),
        valid.latestMeasured(),
        valid.expiresAt());
  }

  /** Publishes ReferenceDataStale once per transition into STALE or EXPIRED. */
  void assessFreshness() {
    var status = status();
    if (status.current() == null) return;
    if (status.warning() != null)
      LoggerFactory.getLogger(ReferenceArchiveApi.class)
          .warn("Reference snapshot {}: {}", status.current().snapshot().reference().id().value(),
              status.warning());
    store.transaction(
        () -> {
          String previous =
              db.queryForObject(
                  "SELECT last_freshness FROM reference_archive_control WHERE source=? FOR UPDATE",
                  String.class,
                  SOURCE);
          var state = status.freshness();
          // Keyed by snapshot so a newer but still stale snapshot is announced too.
          String key = status.current().snapshot().reference().id().value() + ":" + state;
          if (key.equals(previous)) return null;
          db.update(
              "UPDATE reference_archive_control SET last_freshness=? WHERE source=?",
              key,
              SOURCE);
          if (state != Freshness.FRESH)
            store.event(
                "ReferenceDataStale",
                status.current().snapshot().reference().id().value(),
                1,
                UUID.randomUUID(),
                null,
                status);
          return null;
        });
  }
}
