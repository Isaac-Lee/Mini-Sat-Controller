package msc.services.flightdynamics;

import java.util.List;
import java.util.Objects;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeScale;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.ApiException;
import org.springframework.web.bind.annotation.*;

/**
 * Public TAI/UTC conversion through the pinned, digest-verified Orekit reference archive, so
 * clients never approximate leap seconds themselves. UTC strings are zone-less ISO-8601
 * ({@code YYYY-MM-DDThh:mm:ss[.f]}, at least 3 fractional digits on output); a leap second is
 * second 60. Only the integer-second UTC era (from 1972-01-01) is accepted. Instants after the
 * archive's last leap-second entry use its last offset. Read-only; any authenticated role.
 */
@RestController
public class TimeConversionApi {
  static final int MAX_BATCH = 500;
  private static final java.util.regex.Pattern UTC =
      java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:(\\d{2})(\\.\\d{1,9})?");

  public record TaiToUtc(List<MissionInstant> instants) {
    public TaiToUtc {
      instants = List.copyOf(Objects.requireNonNull(instants));
      if (instants.isEmpty() || instants.size() > MAX_BATCH)
        throw ApiException.invalid("Provide 1.." + MAX_BATCH + " instants");
    }
  }

  public record UtcToTai(List<String> utc) {
    public UtcToTai {
      utc = List.copyOf(Objects.requireNonNull(utc));
      if (utc.isEmpty() || utc.size() > MAX_BATCH)
        throw ApiException.invalid("Provide 1.." + MAX_BATCH + " UTC values");
    }
  }

  public record Conversion(MissionInstant tai, String utc) {}

  /** {@code referenceArchiveSha256} identifies the leap-second table that produced every row. */
  public record Conversions(List<Conversion> conversions, String referenceArchiveSha256) {}

  private final OrekitReferenceFrames references;

  private final MissionInstant integerSecondEraStart;

  public TimeConversionApi(OrekitReferenceFrames references) {
    this.references = references;
    this.integerSecondEraStart = references.fromUtc("1972-01-01T00:00:00");
  }

  private void requireIntegerSecondEra(MissionInstant tai) {
    if (tai.compareTo(integerSecondEraStart) < 0)
      throw ApiException.invalid("Only instants from 1972-01-01 UTC are supported");
  }

  @PostMapping("/api/time/tai-to-utc")
  public Conversions taiToUtc(@RequestBody TaiToUtc input) {
    return new Conversions(
        input.instants().stream()
            .map(
                tai -> {
                  if (tai.scale() != TimeScale.TAI) throw ApiException.invalid("Instants must be TAI");
                  requireIntegerSecondEra(tai);
                  return new Conversion(tai, references.toUtc(tai));
                })
            .toList(),
        references.digest());
  }

  // The returned utc is always the canonical rendering of the converted instant, never the echoed
  // input: Orekit silently rolls a non-leap second 60 into the next minute.
  @PostMapping("/api/time/utc-to-tai")
  public Conversions utcToTai(@RequestBody UtcToTai input) {
    return new Conversions(input.utc().stream().map(this::fromUtc).toList(), references.digest());
  }

  private Conversion fromUtc(String utc) {
    var match = UTC.matcher(utc == null ? "" : utc);
    if (!match.matches()) throw ApiException.invalid("UTC must be YYYY-MM-DDThh:mm:ss[.f]");
    MissionInstant tai;
    try {
      tai = references.fromUtc(utc);
    } catch (org.orekit.errors.OrekitException | IllegalArgumentException e) {
      throw ApiException.invalid("Unparseable UTC value");
    }
    requireIntegerSecondEra(tai);
    var canonical = references.toUtc(tai);
    if (match.group(1).equals("60") && !canonical.substring(17, 19).equals("60"))
      throw ApiException.invalid("Second 60 is not a leap second");
    return new Conversion(tai, canonical);
  }
}
