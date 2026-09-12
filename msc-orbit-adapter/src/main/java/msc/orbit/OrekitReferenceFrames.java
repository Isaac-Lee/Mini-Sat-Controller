package msc.orbit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.MissionInstant;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.data.ZipJarCrawler;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

/** Isolated, content-verified reference snapshot. Never modifies Orekit's global DataContext. */
public final class OrekitReferenceFrames {
  private final LazyLoadedDataContext context = new LazyLoadedDataContext();
  private final KeplerianOrbitAdapter time = new KeplerianOrbitAdapter();
  private final String digest;

  public OrekitReferenceFrames(Path archive, String expectedSha256) throws IOException {
    try {
      var sha = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(archive)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
      }
      digest = HexFormat.of().formatHex(sha.digest());
      if (!digest.equals(expectedSha256))
        throw new IllegalArgumentException("Reference archive digest mismatch");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    context.getDataProvidersManager().addProvider(new ZipJarCrawler(archive.toFile()));
    context.getTimeScales().getUTC();
  }

  public MissionInstant fromUtc(String utc) {
    return time.instant(new AbsoluteDate(utc, context.getTimeScales().getUTC()));
  }

  public String toUtc(MissionInstant tai) {
    return time.date(tai).toString(context.getTimeScales().getUTC());
  }

  org.orekit.data.LazyLoadedDataContext context() {
    return context;
  }

  public record Context(
      String digest,
      MissionInstant eopStartInclusive,
      MissionInstant eopEndInclusive,
      String timeModel) {}

  public Context describe() {
    var history = context.getFrames().getEOPHistory(IERSConventions.IERS_2010, false);
    if (history.getEntries().isEmpty()) throw new IllegalStateException("EOP archive is empty");
    return new Context(
        digest,
        time.instant(history.getStartDate()),
        time.instant(history.getEndDate()),
        "UTC-from-pinned-archive");
  }

  public String digest() {
    return digest;
  }

  void requireCoverage(AbsoluteDate date) {
    var history = context.getFrames().getEOPHistory(IERSConventions.IERS_2010, false);
    if (history.getEntries().isEmpty()
        || date.compareTo(history.getStartDate()) < 0
        || date.compareTo(history.getEndDate()) > 0)
      throw new IllegalArgumentException("Earth orientation data do not cover prediction time");
  }

  OneAxisEllipsoid earth() {
    return new OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
        Constants.WGS84_EARTH_FLATTENING,
        context.getFrames().getITRF(IERSConventions.IERS_2010, false));
  }

  public GroundPoint groundPoint(Sample sample) {
    var date = time.date(sample.time());
    requireCoverage(date);
    var earth = earth();
    var point =
        earth.transform(
            KeplerianOrbitAdapter.vector(sample.positionMeters()),
            context.getFrames().getEME2000(),
            date);
    return new GroundPoint(
        sample.time(),
        Math.toDegrees(point.getLatitude()),
        Math.toDegrees(point.getLongitude()),
        point.getAltitude(),
        digest);
  }
}
