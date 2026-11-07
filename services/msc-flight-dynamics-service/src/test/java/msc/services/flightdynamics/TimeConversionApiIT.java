package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import msc.domain.time.*;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.ApiException;
import org.junit.jupiter.api.Test;

/** Run explicitly with MSC_TEST_OREKIT_ARCHIVE/SHA256, like OrekitReferenceFramesIT. */
class TimeConversionApiIT {
  TimeConversionApi api() throws Exception {
    return new TimeConversionApi(
        new OrekitReferenceFrames(
            Path.of(Objects.requireNonNull(System.getenv("MSC_TEST_OREKIT_ARCHIVE"))),
            Objects.requireNonNull(System.getenv("MSC_TEST_OREKIT_SHA256"))));
  }

  @Test
  void batchRoundTripsAcrossTheEndOf2016LeapSecond() throws Exception {
    var api = api();
    var utc =
        List.of("2016-12-31T23:59:59.000", "2016-12-31T23:59:60.000", "2017-01-01T00:00:00.000");
    var tai = api.utcToTai(new TimeConversionApi.UtcToTai(utc));
    assertEquals(utc, tai.conversions().stream().map(TimeConversionApi.Conversion::utc).toList());
    var seconds = tai.conversions().stream().map(c -> c.tai().seconds()).toList();
    assertEquals(List.of(1483228835L, 1483228836L, 1483228837L), seconds);
    var back =
        api.taiToUtc(
            new TimeConversionApi.TaiToUtc(
                tai.conversions().stream().map(TimeConversionApi.Conversion::tai).toList()));
    assertEquals(utc, back.conversions().stream().map(TimeConversionApi.Conversion::utc).toList());
    assertEquals(System.getenv("MSC_TEST_OREKIT_SHA256"), back.referenceArchiveSha256());
  }

  @Test
  void rejectsNonTaiInputUnparseableUtcAndBadBatchSizes() throws Exception {
    var api = api();
    assertThrows(
        ApiException.class,
        () ->
            api.taiToUtc(
                new TimeConversionApi.TaiToUtc(List.of(new MissionInstant(1, 0, TimeScale.UTC)))));
    assertThrows(
        ApiException.class, () -> api.utcToTai(new TimeConversionApi.UtcToTai(List.of("soon"))));
    for (var bad :
        List.of(
            "2020-06-30T23:59:60",
            "2020-01-01T09:00:00+09:00",
            "2020-01-01T00:00:00Z",
            "2020-01-01",
            "1971-06-01T00:00:00"))
      assertThrows(
          ApiException.class,
          () -> api.utcToTai(new TimeConversionApi.UtcToTai(List.of(bad))),
          bad);
    assertThrows(
        ApiException.class,
        () ->
            api.taiToUtc(
                new TimeConversionApi.TaiToUtc(List.of(new MissionInstant(0, 0, TimeScale.TAI)))));
    assertThrows(ApiException.class, () -> new TimeConversionApi.UtcToTai(List.of()));
    assertThrows(
        ApiException.class,
        () -> new TimeConversionApi.UtcToTai(Collections.nCopies(501, "2020-01-01T00:00:00")));
  }
}
