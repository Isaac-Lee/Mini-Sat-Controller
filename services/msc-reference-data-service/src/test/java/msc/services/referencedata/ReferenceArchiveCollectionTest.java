package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import msc.contracts.ReferenceArchiveContracts.*;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ReferenceArchiveCollectionTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  static final String COMMIT = ReferenceArchiveTest.COMMIT;
  @TempDir Path root;
  JdbcTemplate db;
  StateStore store;
  OrekitDataClient provider;
  ReferenceArchiveApi api;
  final AtomicLong now = new AtomicLong(ReferenceArchiveTest.RETRIEVED.seconds());

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox");
    db.update(
        "UPDATE reference_archive_control SET enabled=false,paused=false,lease_token=NULL,"
            + "lease_until=NULL,current_snapshot_id=NULL,last_freshness=NULL,last_error=NULL");
    var json = new Json(JsonMapper.builder().findAndAddModules().build());
    msc.ports.Clock clock = () -> MissionInstant.tai(now.get());
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, clock);
    provider = spy(new OrekitDataClient(json));
    doReturn(COMMIT).when(provider).latestCommit();
    for (String path : ReferenceArchive.FILES)
      doReturn(ReferenceArchiveTest.download(path, ReferenceArchiveTest.real(path)))
          .when(provider)
          .download(COMMIT, path);
    api = new ReferenceArchiveApi(db, store, provider, new ReferenceArchive(root), clock);
  }

  int events(String type) {
    return db.queryForObject(
        "SELECT count(*) FROM outbox WHERE event_type=?", Integer.class, type);
  }

  @Test
  void disabledByDefaultThenPublishesImmutableSnapshotOnce() throws Exception {
    assertTrue(api.claim(true).isEmpty());
    assertNull(api.status().current());
    assertEquals(Freshness.EXPIRED, api.status().freshness());
    api.resume();
    var status = api.refresh();
    var current = status.current();
    assertEquals("orekit-data-" + COMMIT, current.snapshot().reference().id().value());
    assertEquals(Freshness.FRESH, status.freshness());
    assertNull(status.lastError());
    assertTrue(Files.exists(Path.of(current.archivePath())));
    assertEquals(
        current.archiveSha256(),
        ReferenceArchive.sha256(Files.readAllBytes(Path.of(current.archivePath()))));
    assertEquals(1, events("ReferenceSnapshotPublished"));
    // Same upstream commit: no second download, snapshot or event.
    api.refresh();
    verify(provider, times(1)).download(COMMIT, ReferenceArchive.FINALS);
    assertEquals(1, events("ReferenceSnapshotPublished"));
    assertEquals(1, store.history(ReferenceArchiveApi.KIND, current.snapshot().reference().id().value()).size());
    // A lost archive file is rebuilt with identical bytes on the next run.
    Files.delete(Path.of(current.archivePath()));
    api.refresh();
    assertEquals(
        current.archiveSha256(),
        ReferenceArchive.sha256(Files.readAllBytes(Path.of(current.archivePath()))));
    assertEquals(1, events("ReferenceSnapshotPublished"));
    // Cooldown applies to the scheduled path only.
    assertTrue(api.claim(false).isEmpty());
  }

  @Test
  void networkFailureKeepsCurrentAndRetriesWithoutOperator() throws Exception {
    api.resume();
    var original = api.refresh().current();
    doReturn("1111111111111111111111111111111111111111").when(provider).latestCommit();
    doThrow(new java.io.IOException("orekit-data HTTP 503"))
        .when(provider)
        .download(eq("1111111111111111111111111111111111111111"), anyString());
    var status = api.refresh();
    assertEquals(original, status.current());
    assertTrue(status.lastError().contains("HTTP 503"));
    assertTrue(status.collectionEnabled(), "transport failure is retried, not paused");
    assertEquals(1, events("ReferenceSnapshotPublished"));
  }

  @Test
  void invalidDownloadKeepsCurrentAndPausesForReview() throws Exception {
    api.resume();
    var original = api.refresh().current();
    String next = "2222222222222222222222222222222222222222";
    doReturn(next).when(provider).latestCommit();
    byte[] finals = ReferenceArchiveTest.real(ReferenceArchive.FINALS);
    var tampered = finals.clone();
    tampered[2000] ^= 1;
    doReturn(ReferenceArchiveTest.download(ReferenceArchive.TAI_UTC, ReferenceArchiveTest.real(ReferenceArchive.TAI_UTC)))
        .when(provider)
        .download(next, ReferenceArchive.TAI_UTC);
    doReturn(
            new OrekitDataClient.Download(
                ReferenceArchive.FINALS, "u", tampered, ReferenceArchive.gitBlob(finals),
                ReferenceArchive.sha256(finals)))
        .when(provider)
        .download(next, ReferenceArchive.FINALS);
    var status = api.refresh();
    assertEquals(original, status.current());
    assertTrue(status.lastError().contains("integrity mismatch"));
    assertFalse(status.collectionEnabled());
    assertFalse(Files.exists(root.resolve(next).resolve("time-frames.zip")));
    assertThrows(ApiException.class, api::refresh);
    assertTrue(store.find(ReferenceArchiveApi.KIND, "orekit-data-" + next, ArchiveSnapshot.class).isEmpty());
    api.resume();
    assertTrue(api.status().collectionEnabled());
  }

  @Test
  void freshnessTransitionsPublishStaleEventsOncePerState() throws Exception {
    api.resume();
    var current = api.refresh().current();
    api.assessFreshness();
    assertEquals(0, events("ReferenceDataStale"));
    now.set(current.latestMeasured().seconds() + 46 * ReferenceArchive.DAY);
    assertEquals(Freshness.STALE, api.status().freshness());
    api.assessFreshness();
    api.assessFreshness();
    assertEquals(1, events("ReferenceDataStale"));
    now.set(current.expiresAt().seconds());
    var expired = api.status();
    assertEquals(Freshness.EXPIRED, expired.freshness());
    assertTrue(expired.warning().contains("expired"));
    api.assessFreshness();
    assertEquals(2, events("ReferenceDataStale"));
    // Expired data is still the exact current snapshot: never silently dropped or replaced.
    assertEquals(current, expired.current());
  }
}
