package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;

import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.contracts.SimulationTimeCorrelationContracts.Publish;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeScale;
import msc.domain.time.TimeWindow;
import org.junit.jupiter.api.Test;

/**
 * Pure contract tests for {@link Correlation} — no Spring, no database. Covers Slice A acceptance
 * checks A2, A3, A5 from {@code .local/claude-delegation/opus-simulator-execution-plan.md}
 * ("Revision 2" section) plus the additional conversion-correctness cases the coordinator's
 * dispatch enumerated explicitly (carry/borrow, tick-grid misalignment, half-open boundaries,
 * identity/partition mismatch, round-trip exactness). A1/A4/A6 (publish/CAS/role-enforcement over
 * real HTTP) are covered separately in {@code SimulationTimeCorrelationApiTest} in the
 * mission-definition service module.
 */
class SimulationTimeCorrelationContractsTest {
  private static final String SPACECRAFT = "sc-1";
  private static final String MISSION_VERSION = "sim-v1";
  private static final String CORRELATION_ID = "tc-sim-1";
  private static final String PARTITION = "onboard-clock-a";

  private static Correlation correlation(
      MissionInstant taiEpoch, long tickEpoch, long ticksPerSecond, TimeWindow validInterval) {
    return new Correlation(
        SPACECRAFT,
        MISSION_VERSION,
        CORRELATION_ID,
        PARTITION,
        taiEpoch,
        tickEpoch,
        ticksPerSecond,
        validInterval,
        "SIMULATION",
        "approval-1",
        "synthetic-test-fixture");
  }

  private static TimeWindow wideInterval() {
    return new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(10_000_000));
  }

  // --- A2 / round-trip exactness -----------------------------------------------------------

  @Test
  void exactConversionBeforeAndAfterEpoch() {
    // 10 ticks/second -> 100ms/tick.
    var c = correlation(MissionInstant.tai(1_000_000), 500, 10, wideInterval());

    assertEquals(new MissionInstant(1_000_000, 0, TimeScale.TAI), c.tickToTai(CORRELATION_ID, PARTITION, 500));
    // After epoch: elapsedTicks = +5 -> +500ms.
    assertEquals(
        new MissionInstant(1_000_000, 500_000_000, TimeScale.TAI),
        c.tickToTai(CORRELATION_ID, PARTITION, 505));
    // Before epoch: elapsedTicks = -5 -> -500ms, borrowing a whole second.
    assertEquals(
        new MissionInstant(999_999, 500_000_000, TimeScale.TAI),
        c.tickToTai(CORRELATION_ID, PARTITION, 495));
  }

  @Test
  void nanosecondCarryAcrossSecondBoundaryForward() {
    // taiEpoch has a nonzero fraction (0.9s); +2 ticks (200ms) rolls into the next second.
    var c = correlation(new MissionInstant(1_000_000, 900_000_000, TimeScale.TAI), 500, 10, wideInterval());
    assertEquals(
        new MissionInstant(1_000_001, 100_000_000, TimeScale.TAI),
        c.tickToTai(CORRELATION_ID, PARTITION, 502));
  }

  @Test
  void nanosecondBorrowAcrossSecondBoundaryBackward() {
    // taiEpoch = 1_000_000.9s; -3 ticks (-300ms) -> 1_000_000.6s: a borrow into the prior second
    // that then re-carries forward once taiEpoch's own 0.9s fraction is added back in.
    var c = correlation(new MissionInstant(1_000_000, 900_000_000, TimeScale.TAI), 500, 10, wideInterval());
    assertEquals(
        new MissionInstant(1_000_000, 600_000_000, TimeScale.TAI),
        c.tickToTai(CORRELATION_ID, PARTITION, 497));
  }

  @Test
  void roundTripTickToTaiToTickIsExactAcrossARange() {
    var c = correlation(MissionInstant.tai(1_000_000), 500, 10, wideInterval());
    for (long tick = 480; tick <= 520; tick++) {
      var instant = c.tickToTai(CORRELATION_ID, PARTITION, tick);
      assertEquals(tick, c.taiToTick(CORRELATION_ID, PARTITION, instant), "round-trip mismatch at tick " + tick);
    }
  }

  @Test
  void negativeInputTickRejectedExplicitlyEvenWhenItWouldOtherwiseLandInsideTheWindow() {
    // A positive tickEpoch and a valid interval broad enough that tick = -1 would, if not
    // explicitly rejected, both avoid overflow and convert to a TAI instant safely inside the
    // window -- so a passing assertion here proves a real, dedicated guard exists rather than
    // riding on the overflow check or the validity-window check catching it by coincidence.
    var c =
        correlation(
            MissionInstant.tai(1_000_000),
            500,
            10,
            new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(10_000_000)));
    assertThrows(IllegalArgumentException.class, () -> c.tickToTai(CORRELATION_ID, PARTITION, -1));
  }

  // --- A3 / overflow -------------------------------------------------------------------------

  @Test
  void tickToTaiOverflowRejectedRatherThanWrapped() {
    var c = correlation(MissionInstant.tai(0), 0, 1, wideInterval());
    assertThrows(
        ArithmeticException.class, () -> c.tickToTai(CORRELATION_ID, PARTITION, Long.MAX_VALUE));
  }

  @Test
  void taiToTickOverflowRejectedRatherThanWrapped() {
    // A validity interval wide enough to admit a TAI instant whose seconds value, once
    // multiplied out to nanoseconds, overflows a long -- proving the overflow is caught by the
    // checked arithmetic, not masked by the validity check.
    var c =
        correlation(
            MissionInstant.tai(0),
            0,
            1,
            new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(Long.MAX_VALUE)));
    var farInstant = MissionInstant.tai(9_300_000_000L);
    assertThrows(
        ArithmeticException.class, () -> c.taiToTick(CORRELATION_ID, PARTITION, farInstant));
  }

  // --- A5 / invalid frequencies ----------------------------------------------------------------

  @Test
  void nonPositiveOrOversizedOrNonDivisorFrequenciesRejected() {
    for (long badFrequency : new long[] {0, -1, 1_000_000_001L, 3}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> correlation(MissionInstant.tai(0), 0, badFrequency, wideInterval()),
          "expected rejection for ticksPerSecond=" + badFrequency);
    }
    // A genuine divisor of 1e9 that is not 10 is accepted, proving the check is "divides 1e9",
    // not merely "one of a hardcoded few values".
    assertDoesNotThrow(() -> correlation(MissionInstant.tai(0), 0, 4, wideInterval()));
    assertDoesNotThrow(() -> correlation(MissionInstant.tai(0), 0, 1_000_000_000L, wideInterval()));
  }

  @Test
  void negativeTickEpochRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> correlation(MissionInstant.tai(0), -1, 10, wideInterval()));
  }

  @Test
  void nonSimulationEnvironmentRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Correlation(
                SPACECRAFT,
                MISSION_VERSION,
                CORRELATION_ID,
                PARTITION,
                MissionInstant.tai(0),
                0,
                10,
                wideInterval(),
                "HARDWARE",
                "approval-1",
                "t"));
  }

  @Test
  void nonTaiEpochRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> correlation(new MissionInstant(0, 0, TimeScale.UTC), 0, 10, wideInterval()));
  }

  // --- Tick-grid misalignment ------------------------------------------------------------------

  @Test
  void taiToTickMisalignmentRejectedRatherThanRounded() {
    // 100ms/tick; an instant 50ms off the grid must be rejected, never rounded.
    var c = correlation(MissionInstant.tai(1_000_000), 500, 10, wideInterval());
    var misaligned = new MissionInstant(1_000_000, 50_000_000, TimeScale.TAI);
    assertThrows(
        IllegalArgumentException.class, () -> c.taiToTick(CORRELATION_ID, PARTITION, misaligned));
  }

  // --- Half-open validity interval boundaries ---------------------------------------------------

  @Test
  void validityIntervalIsHalfOpenAtBothEnds() {
    var start = MissionInstant.tai(1_000_000);
    var end = MissionInstant.tai(1_000_001);
    var c = correlation(start, 500, 10, new TimeWindow(start, end));

    // Start is inclusive: tick 500 maps exactly to `start`.
    assertEquals(start, c.tickToTai(CORRELATION_ID, PARTITION, 500));
    assertEquals(500L, c.taiToTick(CORRELATION_ID, PARTITION, start));

    // Just before `end` (tick 509, +900ms) is inside the interval.
    var justBeforeEnd = new MissionInstant(1_000_000, 900_000_000, TimeScale.TAI);
    assertEquals(justBeforeEnd, c.tickToTai(CORRELATION_ID, PARTITION, 509));
    assertEquals(509L, c.taiToTick(CORRELATION_ID, PARTITION, justBeforeEnd));

    // `end` itself (tick 510, +1000ms) is excluded on both directions.
    assertThrows(
        IllegalArgumentException.class, () -> c.tickToTai(CORRELATION_ID, PARTITION, 510));
    assertThrows(
        IllegalArgumentException.class, () -> c.taiToTick(CORRELATION_ID, PARTITION, end));
  }

  // --- Identity / partition mismatch ------------------------------------------------------------

  @Test
  void mismatchedTimeCorrelationIdRejectedOnBothDirections() {
    var c = correlation(MissionInstant.tai(1_000_000), 500, 10, wideInterval());
    assertThrows(
        IllegalArgumentException.class, () -> c.tickToTai("not-" + CORRELATION_ID, PARTITION, 500));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            c.taiToTick(
                "not-" + CORRELATION_ID, PARTITION, new MissionInstant(1_000_000, 0, TimeScale.TAI)));
  }

  @Test
  void mismatchedClockPartitionRejectedOnBothDirections() {
    var c = correlation(MissionInstant.tai(1_000_000), 500, 10, wideInterval());
    assertThrows(
        IllegalArgumentException.class,
        () -> c.tickToTai(CORRELATION_ID, "not-" + PARTITION, 500));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            c.taiToTick(
                CORRELATION_ID, "not-" + PARTITION, new MissionInstant(1_000_000, 0, TimeScale.TAI)));
  }

  // --- Negative resulting tick ------------------------------------------------------------------

  @Test
  void negativeResultingTickRejected() {
    var c =
        correlation(
            MissionInstant.tai(1_000_000),
            5,
            10,
            new TimeWindow(MissionInstant.tai(999_000), MissionInstant.tai(1_001_000)));
    // 1s before taiEpoch = -10 ticks -> tickEpoch(5) - 10 = -5.
    var oneSecondBefore = MissionInstant.tai(999_999);
    assertThrows(
        IllegalArgumentException.class, () -> c.taiToTick(CORRELATION_ID, PARTITION, oneSecondBefore));
  }

  // --- Publish envelope ---------------------------------------------------------------------

  @Test
  void publishRequiresNonNegativeExpectedVersionAndNonNullCorrelation() {
    var c = correlation(MissionInstant.tai(0), 0, 10, wideInterval());
    assertThrows(IllegalArgumentException.class, () -> new Publish(-1, c));
    assertThrows(IllegalArgumentException.class, () -> new Publish(0, null));
    assertDoesNotThrow(() -> new Publish(0, c));
  }
}
