package msc.orbit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConditionalSolarIntervalTest {
  @Test
  void midpointCanBeBrightWhileIntervalCannotBeEstablished() {
    var assumption = new ConditionalSolarInterval.Assumptions(.1, .001, 10, "synthetic triangular function");
    var result = ConditionalSolarInterval.evaluate(10, assumption, t -> .2 - .1 * Math.abs(t - 5));
    assertEquals(.2, result.cells().getFirst().sampledLowerElevationRadians(), 1e-15);
    assertTrue(result.lowerElevationRadians() < -.3);
    for (int i = 0; i <= 1000; i++)
      assertTrue(result.lowerElevationRadians() <= .2 - .1 * Math.abs(i / 100.0 - 5));
  }

  @Test
  void cellPartitionCoversRemainderAndBoundsIndependentSinusoid() {
    var assumption = new ConditionalSolarInterval.Assumptions(.01, .0001, 3, "synthetic sine rate proven analytically");
    var result = ConditionalSolarInterval.evaluate(10, assumption, t -> .4 + .1 * Math.sin(.1 * t));
    assertEquals(4, result.cells().size());
    double end = 0;
    for (var cell : result.cells()) {
      assertEquals(end, cell.startSeconds());
      assertTrue(cell.endSeconds() - cell.startSeconds() <= 3);
      for (int i = 0; i <= 100; i++) {
        double t = cell.startSeconds() + (cell.endSeconds() - cell.startSeconds()) * i / 100;
        assertTrue(cell.intervalLowerElevationRadians() <= .4 + .1 * Math.sin(.1 * t));
      }
      end = cell.endSeconds();
    }
    assertEquals(10, end);
    assertTrue(result.lowerElevationRadians() > .38);
  }

  @Test
  void invalidBudgetAndNonfiniteEvaluationCannotProduceEvidence() {
    var assumption = new ConditionalSolarInterval.Assumptions(.01, .001, 1, "test");
    assertThrows(IllegalArgumentException.class, () -> ConditionalSolarInterval.evaluate(4097, assumption, t -> 0));
    assertThrows(IllegalArgumentException.class, () -> ConditionalSolarInterval.evaluate(1, assumption, t -> Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> new ConditionalSolarInterval.Assumptions(.01, 0, 1, "test"));
    assertThrows(IllegalArgumentException.class, () -> new ConditionalSolarInterval.Assumptions(-1, .001, 1, "test"));
  }
}
