package msc.services.referencedata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import msc.contracts.ReferenceArchiveContracts.*;
import msc.domain.time.MissionInstant;
import msc.orbit.OrekitReferenceFrames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates downloaded orekit-data files and writes the deterministic archive flight-dynamics
 * loads. Fails closed: nothing is placed at the final path unless every check passes.
 */
@Component
public class ReferenceArchive {
  static final String TAI_UTC = "tai-utc.dat";
  static final String FINALS = "Earth-Orientation-Parameters/IAU-2000/finals2000A.all";
  static final List<String> FILES = List.of(FINALS, TAI_UTC); // zip entry order, sorted
  static final long DAY = 86400;
  // See docs/backend/reference-archive.md for the derivation of these thresholds.
  static final long STALE_AFTER_MEASURED = 45 * DAY;
  static final long LEAP_SECOND_HORIZON = 150 * DAY;
  static final long WARN_BEFORE_EXPIRY = 30 * DAY;

  private static final long MJD_1972 = 41317;
  private static final LocalDate MJD_EPOCH = LocalDate.of(1858, 11, 17);
  private static final Pattern TAI_UTC_LINE =
      Pattern.compile(
          " (\\d{4}) [A-Z]{3} [ \\d]\\d =JD (\\d{7})\\.5  TAI-UTC= *(\\d+\\.\\d+) +S \\+ \\(MJD"
              + " - (\\d+)\\.\\) X (\\d+\\.\\d+) *S *");

  private final Path root;

  public ReferenceArchive(@Value("${msc.reference.archive-dir}") Path root) {
    this.root = root;
  }

  public record Validated(
      Path archive,
      String sha256,
      List<SourceFile> files,
      MissionInstant eopStart,
      MissionInstant eopEnd,
      MissionInstant latestMeasured,
      MissionInstant expiresAt) {}

  record Finals(long latestMeasuredMjd, long lastPredictedMjd, SortedSet<Long> ut1Jumps,
      long firstUt1Mjd) {}

  public Validated validate(
      String commit, List<OrekitDataClient.Download> downloads, MissionInstant now)
      throws IOException {
    if (!commit.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid commit");
    var byPath = new TreeMap<String, OrekitDataClient.Download>();
    for (var d : downloads) byPath.put(d.path(), d);
    if (!byPath.keySet().equals(new TreeSet<>(FILES)))
      throw new IllegalArgumentException("Archive requires exactly " + FILES);
    var files = new ArrayList<SourceFile>();
    for (var d : byPath.values()) {
      String blob = gitBlob(d.data()), sha = sha256(d.data());
      if (!blob.equals(d.gitBlob()) || !sha.equals(d.sha256()))
        throw new IllegalArgumentException("Official file integrity mismatch: " + d.path());
      files.add(new SourceFile(d.path(), d.url(), blob, sha, d.data().length));
    }
    var leaps = leapSeconds(text(byPath.get(TAI_UTC)));
    var finals = finals(text(byPath.get(FINALS)));
    var expectedJumps = leaps.subSet(finals.firstUt1Mjd() + 1, finals.lastPredictedMjd() + 1);
    if (!expectedJumps.equals(finals.ut1Jumps()))
      throw new IllegalArgumentException(
          "UT1-UTC jumps " + finals.ut1Jumps() + " disagree with leap seconds " + expectedJumps);

    Path directory = Files.createDirectories(root.resolve(commit));
    Path target = directory.resolve("time-frames.zip");
    Path partial = Files.createTempFile(directory, "time-frames", ".part");
    try {
      try (var zip = new ZipOutputStream(Files.newOutputStream(partial))) {
        for (var d : byPath.values()) {
          var entry = new ZipEntry(d.path());
          var crc = new CRC32();
          crc.update(d.data());
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(d.data().length);
          entry.setCompressedSize(d.data().length);
          entry.setCrc(crc.getValue());
          entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
          zip.putNextEntry(entry);
          zip.write(d.data());
          zip.closeEntry();
        }
      }
      String digest = sha256(Files.readAllBytes(partial));
      // The exact loader flight-dynamics uses. Orekit also counts the date-only rows that end
      // real files, so the measured/predicted UT1 rows parsed above define the coverage end.
      var frames = new OrekitReferenceFrames(partial, digest);
      var context = frames.describe();
      var eopEnd = frames.fromUtc(utc(finals.lastPredictedMjd()));
      if (context.eopEndInclusive().compareTo(eopEnd) < 0)
        throw new IllegalArgumentException("Orekit did not load all finals2000A.all UT1 rows");
      var measured = frames.fromUtc(utc(finals.latestMeasuredMjd()));
      var horizon = MissionInstant.tai(measured.seconds() + LEAP_SECOND_HORIZON);
      var expires = horizon.compareTo(eopEnd) < 0 ? horizon : eopEnd;
      if (now.compareTo(context.eopStartInclusive()) < 0 || now.compareTo(expires) >= 0)
        throw new IllegalArgumentException("Reference data coverage does not include now");
      Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return new Validated(
          target, digest, files, context.eopStartInclusive(), eopEnd, measured, expires);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Invalid reference archive: " + error.getMessage(), error);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /** MJDs of leap seconds (start of the day UTC jumps); rejects any non-integral post-1972 step. */
  static SortedSet<Long> leapSeconds(String raw) {
    var leaps = new TreeSet<Long>();
    long previousMjd = 0;
    double previousOffset = Double.NaN;
    for (String line : raw.split("\n")) {
      if (line.isBlank()) continue;
      var m = TAI_UTC_LINE.matcher(line);
      if (!m.matches()) throw new IllegalArgumentException("Malformed tai-utc.dat line: " + line);
      long mjd = Long.parseLong(m.group(2)) - 2400000;
      double offset = Double.parseDouble(m.group(3));
      if (mjd <= previousMjd) throw new IllegalArgumentException("tai-utc.dat not increasing");
      if (mjd >= MJD_1972) {
        if (Double.parseDouble(m.group(5)) != 0.0 || offset != Math.rint(offset))
          throw new IllegalArgumentException("Post-1972 TAI-UTC must be integral seconds");
        if (mjd > MJD_1972) {
          if (Math.abs(Math.abs(offset - previousOffset) - 1.0) > 1e-9)
            throw new IllegalArgumentException("Leap second step must be one second");
          leaps.add(mjd);
        }
      }
      previousMjd = mjd;
      previousOffset = offset;
    }
    if (leaps.isEmpty()) throw new IllegalArgumentException("tai-utc.dat has no leap seconds");
    return leaps;
  }

  /** IERS finals2000A.all fixed columns: date 1-6, MJD 8-15, UT1 flag 58, UT1-UTC 59-68. */
  static Finals finals(String raw) {
    long expectedMjd = -1, latestMeasured = -1, lastPredicted = -1, firstUt1 = -1;
    double previousUt1 = Double.NaN;
    boolean ended = false;
    var jumps = new TreeSet<Long>();
    for (String line : raw.split("\n")) {
      if (line.length() < 15) throw new IllegalArgumentException("Truncated finals2000A.all row");
      long mjd;
      LocalDate date;
      try {
        double value = Double.parseDouble(line.substring(7, 15).trim());
        mjd = (long) value;
        if (value != mjd) throw new NumberFormatException("fractional MJD");
        date = MJD_EPOCH.plusDays(mjd);
        if (Integer.parseInt(line.substring(0, 2).trim()) != date.getYear() % 100
            || Integer.parseInt(line.substring(2, 4).trim()) != date.getMonthValue()
            || Integer.parseInt(line.substring(4, 6).trim()) != date.getDayOfMonth())
          throw new IllegalArgumentException("finals2000A.all date disagrees with MJD " + mjd);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("Malformed finals2000A.all row: " + line, error);
      }
      if (expectedMjd >= 0 && mjd != expectedMjd)
        throw new IllegalArgumentException("finals2000A.all days not consecutive at MJD " + mjd);
      expectedMjd = mjd + 1;
      char flag = line.length() > 57 ? line.charAt(57) : ' ';
      if (flag == ' ') {
        // Real files end with date-only rows; a blank row anywhere else is a coverage gap.
        if (lastPredicted < 0) throw new IllegalArgumentException("finals2000A.all lacks UT1");
        ended = true;
        continue;
      }
      if (ended) throw new IllegalArgumentException("UT1-UTC gap before MJD " + mjd);
      if ((flag != 'I' && flag != 'P') || line.length() < 68)
        throw new IllegalArgumentException("Malformed UT1 flag at MJD " + mjd);
      if (flag == 'I') {
        if (lastPredicted > latestMeasured)
          throw new IllegalArgumentException("Measured UT1 after prediction at MJD " + mjd);
        latestMeasured = mjd;
      }
      double ut1;
      try {
        ut1 = Double.parseDouble(line.substring(58, 68).trim());
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("Malformed UT1-UTC at MJD " + mjd, error);
      }
      if (firstUt1 < 0) firstUt1 = mjd;
      if (Math.abs(ut1 - previousUt1) > 0.5) jumps.add(mjd);
      previousUt1 = ut1;
      lastPredicted = mjd;
    }
    if (latestMeasured < 0 || lastPredicted <= latestMeasured)
      throw new IllegalArgumentException("finals2000A.all lacks measured and predicted UT1");
    return new Finals(latestMeasured, lastPredicted, jumps, firstUt1);
  }

  public static Freshness freshness(MissionInstant now, ArchiveSnapshot snapshot) {
    if (now.compareTo(snapshot.expiresAt()) >= 0) return Freshness.EXPIRED;
    if (now.seconds() - snapshot.latestMeasured().seconds() > STALE_AFTER_MEASURED
        || snapshot.expiresAt().seconds() - now.seconds() <= WARN_BEFORE_EXPIRY)
      return Freshness.STALE;
    return Freshness.FRESH;
  }

  public static String warning(MissionInstant now, ArchiveSnapshot snapshot) {
    long remaining = snapshot.expiresAt().seconds() - now.seconds();
    if (remaining <= 0) return "Reference data expired; UTC/UT1 conversions are not valid";
    if (remaining <= WARN_BEFORE_EXPIRY)
      return "Reference data expires in " + Math.ceilDiv(remaining, DAY) + " day(s)";
    if (now.seconds() - snapshot.latestMeasured().seconds() > STALE_AFTER_MEASURED)
      return "Latest measured EOP is older than " + STALE_AFTER_MEASURED / DAY + " days";
    return null;
  }

  private static String utc(long mjd) {
    return MJD_EPOCH.plusDays(mjd) + "T00:00:00.000";
  }

  private static String text(OrekitDataClient.Download d) {
    return new String(d.data(), StandardCharsets.US_ASCII);
  }

  static String gitBlob(byte[] data) {
    return digest("SHA-1", ("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII), data);
  }

  static String sha256(byte[] data) {
    return digest("SHA-256", new byte[0], data);
  }

  private static String digest(String algorithm, byte[] prefix, byte[] data) {
    try {
      var digest = MessageDigest.getInstance(algorithm);
      digest.update(prefix);
      return HexFormat.of().formatHex(digest.digest(data));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
