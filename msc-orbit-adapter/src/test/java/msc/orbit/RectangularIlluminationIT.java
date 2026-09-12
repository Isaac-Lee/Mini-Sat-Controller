package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.AnalyticalSolarPositionProvider;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;

class RectangularIlluminationIT {
  private OrekitReferenceFrames references() throws Exception {
    return new OrekitReferenceFrames(Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")),
        System.getenv("MSC_TEST_OREKIT_SHA256"));
  }

  @Test
  void fullRectangleWindowsAgreeWithIndependentTopocentricGridAcrossDayAndNight() throws Exception {
    var refs = references();
    var start = refs.fromUtc("2026-09-01T00:00:00");
    var horizon = new TimeWindow(start, start.plus(new MissionDuration(86_400_000_000_000L)));
    var area = new RectangularSolarElevation.Rectangle(-10, 10, -10, 10, 10000);
    var result = new OrekitIlluminationPredictor(refs).rectangularIllumination(horizon, area, 10);
    assertFalse(result.illuminatedWindows().isEmpty());
    var noon = refs.fromUtc("2026-09-01T12:00:00");
    assertTrue(result.illuminatedWindows().stream().anyMatch(w -> w.start().compareTo(noon) <= 0 && w.end().compareTo(noon) > 0));
    assertFalse(result.illuminatedWindows().stream().anyMatch(w -> w.start().compareTo(start) <= 0 && w.end().compareTo(start) > 0));
    var sun = new AnalyticalSolarPositionProvider(refs.context());
    var time = new KeplerianOrbitAdapter();
    int checked = 0;
    for (var window : result.illuminatedWindows()) {
      assertTrue(horizon.contains(window));
      // Stay a second inside numerical roots; test geometry using a different computation.
      for (var instant = window.start().plus(new MissionDuration(1_000_000_000L));
          instant.plus(new MissionDuration(1_000_000_000L)).compareTo(window.end()) < 0;
          instant = instant.plus(new MissionDuration(600_000_000_000L))) {
        var date = time.date(instant);
        var position = sun.getPosition(date, refs.earth().getBodyFrame());
        for (int lat = -10; lat <= 10; lat += 2) {
          for (int lon = -10; lon <= 10; lon += 2) {
            var ground = new TopocentricFrame(refs.earth(),
                new GeodeticPoint(Math.toRadians(lat), Math.toRadians(lon), 10000), "grid");
            assertTrue(Math.toDegrees(ground.getElevation(position, refs.earth().getBodyFrame(), date)) >= 10);
            checked++;
          }
        }
      }
    }
    assertTrue(checked > 1000);
  }

  @Test
  void wideAreaCannotMeetNearZenithThresholdAndOversizedSearchIsRejected() throws Exception {
    var refs = references();
    var start = refs.fromUtc("2026-09-01T12:00:00");
    var predictor = new OrekitIlluminationPredictor(refs);
    var area = new RectangularSolarElevation.Rectangle(-30, 30, -20, 20, 0);
    var horizon = new TimeWindow(start, start.plus(new MissionDuration(3600_000_000_000L)));
    assertTrue(predictor.rectangularIllumination(horizon, area, 89).illuminatedWindows().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> predictor.rectangularIllumination(
        new TimeWindow(start, start.plus(new MissionDuration(86_401_000_000_000L))), area, 10));
    assertThrows(IllegalArgumentException.class, () -> predictor.rectangularIllumination(horizon, area, Double.NaN));
  }
}
