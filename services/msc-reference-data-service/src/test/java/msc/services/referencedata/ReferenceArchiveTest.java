package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import msc.contracts.ReferenceArchiveContracts.*;
import msc.domain.time.MissionInstant;
import msc.orbit.OrekitReferenceFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real orekit-data commit 2d6edc91 (see src/test/resources/orekit-data-2d6edc91/SOURCE.md). */
class ReferenceArchiveTest {
  static final String COMMIT = "2d6edc9118983d95c51303df05db16ada90b7ee2";
  static final Map<String, String> BLOBS =
      Map.of(
          ReferenceArchive.TAI_UTC, "89d16226f380b98240d9e47de4a845396d3c0ac0",
          ReferenceArchive.FINALS, "0433b30b099adb572277d6c36b4a2b99c0fa495c");
  /** 2026-11-07T00:00:00 UTC, the retrieval date, as TAI (TAI-UTC = 37 s). */
  static final MissionInstant RETRIEVED = MissionInstant.tai(1794009600L + 37);

  @TempDir Path root;

  static byte[] real(String path) throws Exception {
    String base = "/orekit-data-2d6edc91/";
    if (path.equals(ReferenceArchive.TAI_UTC))
      try (var in = ReferenceArchiveTest.class.getResourceAsStream(base + "tai-utc.dat")) {
        return in.readAllBytes();
      }
    try (var in =
        new GZIPInputStream(
            ReferenceArchiveTest.class.getResourceAsStream(base + "finals2000A.all.gz"))) {
      return in.readAllBytes();
    }
  }

  static OrekitDataClient.Download download(String path, byte[] data) {
    return new OrekitDataClient.Download(
        path,
        OrekitDataClient.RAW + COMMIT + "/" + path,
        data,
        ReferenceArchive.gitBlob(data),
        ReferenceArchive.sha256(data));
  }

  static List<OrekitDataClient.Download> realDownloads() throws Exception {
    var list = new ArrayList<OrekitDataClient.Download>();
    for (String path : ReferenceArchive.FILES) list.add(download(path, real(path)));
    return list;
  }

  static ArchiveSnapshot snapshot(ReferenceArchive.Validated v) {
    return new ArchiveSnapshot(
        null, COMMIT, v.archive().toString(), v.sha256(), v.files(), v.eopStart(), v.eopEnd(),
        v.latestMeasured(), v.expiresAt());
  }

  @Test
  void fixturesAreTheUnmodifiedOfficialFiles() throws Exception {
    for (var entry : BLOBS.entrySet())
      assertEquals(entry.getValue(), ReferenceArchive.gitBlob(real(entry.getKey())));
  }

  @Test
  void validRealSnapshotLoadsInOrekitWithExplicitCoverage() throws Exception {
    var archive = new ReferenceArchive(root);
    var valid = archive.validate(COMMIT, realDownloads(), RETRIEVED);
    assertEquals(root.resolve(COMMIT).resolve("time-frames.zip"), valid.archive());
    var frames = new OrekitReferenceFrames(valid.archive(), valid.sha256());
    // Last IERS-measured UT1 day and last predicted day in this real file.
    assertEquals(frames.fromUtc("2026-11-05T00:00:00.000"), valid.latestMeasured());
    assertEquals(frames.fromUtc("2027-11-12T00:00:00.000"), valid.eopEnd());
    assertEquals(
        valid.latestMeasured().seconds() + ReferenceArchive.LEAP_SECOND_HORIZON,
        valid.expiresAt().seconds());
    // The real 2016-12-31 leap second is honoured by the archive.
    assertEquals(
        1, frames.fromUtc("2017-01-01T00:00:00.000").seconds()
            - frames.fromUtc("2016-12-31T23:59:59.000").seconds() - 1);
    assertEquals(
        List.of(ReferenceArchive.FINALS, ReferenceArchive.TAI_UTC),
        valid.files().stream().map(SourceFile::path).toList());
    // Deterministic bytes: an independent rebuild yields the same digest.
    var again =
        new ReferenceArchive(Files.createTempDirectory(root, "again"))
            .validate(COMMIT, realDownloads(), RETRIEVED);
    assertEquals(valid.sha256(), again.sha256());
    try (var listing = Files.list(root.resolve(COMMIT))) {
      assertEquals(List.of(valid.archive()), listing.toList());
    }
  }

  @Test
  void realLeapSecondTableAndUt1JumpsAgree() throws Exception {
    var leaps =
        ReferenceArchive.leapSeconds(new String(real(ReferenceArchive.TAI_UTC), StandardCharsets.US_ASCII));
    assertEquals(27, leaps.size()); // 1972-07-01 .. 2017-01-01
    assertEquals(57754L, leaps.last()); // 2017-01-01
    var finals =
        ReferenceArchive.finals(new String(real(ReferenceArchive.FINALS), StandardCharsets.US_ASCII));
    assertEquals(leaps.tailSet(finals.firstUt1Mjd() + 1), finals.ut1Jumps());
  }

  @Test
  void invalidFilesNeverReachTheArchivePath() throws Exception {
    var archive = new ReferenceArchive(root);
    byte[] tai = real(ReferenceArchive.TAI_UTC), finals = real(ReferenceArchive.FINALS);
    String taiText = new String(tai, StandardCharsets.US_ASCII);
    String finalsText = new String(finals, StandardCharsets.US_ASCII);
    int middle = finalsText.indexOf('\n', finalsText.length() / 2) + 1;
    var tampered = finals.clone();
    tampered[1000] ^= 1;
    var cases =
        Map.of(
            "provider hash mismatch",
            List.of(
                new OrekitDataClient.Download(
                    ReferenceArchive.FINALS, "u", tampered, BLOBS.get(ReferenceArchive.FINALS),
                    ReferenceArchive.sha256(finals)),
                download(ReferenceArchive.TAI_UTC, tai)),
            "HTML error page",
            List.of(
                download(ReferenceArchive.FINALS, "<html>502</html>\n".getBytes()),
                download(ReferenceArchive.TAI_UTC, tai)),
            "2017 leap second removed",
            List.of(
                download(ReferenceArchive.FINALS, finals),
                download(
                    ReferenceArchive.TAI_UTC,
                    taiText.substring(0, taiText.indexOf(" 2017 JAN")).getBytes())),
            "EOP day removed",
            List.of(
                download(
                    ReferenceArchive.FINALS,
                    (finalsText.substring(0, middle)
                            + finalsText.substring(finalsText.indexOf('\n', middle) + 1))
                        .getBytes()),
                download(ReferenceArchive.TAI_UTC, tai)),
            "missing file",
            List.of(download(ReferenceArchive.TAI_UTC, tai)));
    for (var entry : cases.entrySet())
      assertThrows(
          IllegalArgumentException.class,
          () -> archive.validate(COMMIT, entry.getValue(), RETRIEVED),
          entry.getKey());
    var expired = MissionInstant.tai(RETRIEVED.seconds() + 400 * ReferenceArchive.DAY);
    assertThrows(
        IllegalArgumentException.class, () -> archive.validate(COMMIT, realDownloads(), expired));
    try (var walk = Files.walk(root)) {
      assertTrue(walk.noneMatch(Files::isRegularFile), "no archive or partial file left behind");
    }
  }

  @Test
  void freshnessTransitionsAtDocumentedThresholds() throws Exception {
    var s = snapshot(new ReferenceArchive(root).validate(COMMIT, realDownloads(), RETRIEVED));
    long measured = s.latestMeasured().seconds(), expires = s.expiresAt().seconds();
    long day = ReferenceArchive.DAY;
    assertEquals(Freshness.FRESH, ReferenceArchive.freshness(RETRIEVED, s));
    assertNull(ReferenceArchive.warning(RETRIEVED, s));
    var edge = MissionInstant.tai(measured + 45 * day);
    assertEquals(Freshness.FRESH, ReferenceArchive.freshness(edge, s));
    var stale = MissionInstant.tai(measured + 45 * day + 1);
    assertEquals(Freshness.STALE, ReferenceArchive.freshness(stale, s));
    assertTrue(ReferenceArchive.warning(stale, s).contains("older than 45 days"));
    var near = MissionInstant.tai(expires - 30 * day);
    assertEquals(Freshness.STALE, ReferenceArchive.freshness(near, s));
    assertEquals("Reference data expires in 30 day(s)", ReferenceArchive.warning(near, s));
    var last = MissionInstant.tai(expires - 1);
    assertEquals(Freshness.STALE, ReferenceArchive.freshness(last, s));
    var gone = MissionInstant.tai(expires);
    assertEquals(Freshness.EXPIRED, ReferenceArchive.freshness(gone, s));
    assertTrue(ReferenceArchive.warning(gone, s).contains("expired"));
  }
}
