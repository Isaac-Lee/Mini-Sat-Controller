package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeScale;
import msc.domain.time.TimeWindow;

/**
 * ADMIN-published, versioned, per-spacecraft <b>SIMULATION</b> onboard tick &harr; TAI
 * correlation, owned by Mission Definition, following the same owner pattern as {@code
 * AgilityContracts} / {@code SimulationPlanningContracts} / {@code OperationResourceContracts}.
 *
 * <p><b>Why this exists.</b> {@code msc.domain.shared.Ids.TimeCorrelationId} appears elsewhere in
 * this codebase only as an identity string used for binding checks ({@code
 * msc.domain.spacecraftcontrol.CommandLoad}, {@code CommandReleasePolicy.Binding},
 * {@code CatalogContracts.MissionProfile#timeCorrelationId()}). Nothing, anywhere, publishes an
 * actual ticks &harr; TAI conversion function, so {@code msc.domain.time.OnboardTime#ticks()} has
 * no defined meaning today. This contract closes exactly that gap: it defines the conversion
 * function and its exact validity window. It does <b>not</b> implement an executor, a command
 * ledger, or any physical effect, and it must never be represented as doing so.
 *
 * <p><b>Both conversion directions require an explicit, pinned correlation instance.</b> A
 * consumer must resolve the exact published version it intends to use (via {@code
 * SimulationTimeCorrelationApi}'s current or exact-historical-version reads) and call the
 * conversion methods on that resolved {@link Correlation} directly. There is no "latest"
 * resolution built into the conversion itself, and no correlation may ever be guessed or derived
 * from NORAD identity or any public orbit: SPACEEYE-T1 / NORAD 63229 is public GP orbit tracking
 * only, never a source of clock truth.
 *
 * <p><b>Representation constraint on {@code ticksPerSecond} — an explicit simulation choice, not
 * a real hardware limit.</b> Real onboard oscillators are not exact divisors of one billion. This
 * contract deliberately restricts {@code ticksPerSecond} to a positive integer, at most {@link
 * Correlation#MAX_TICKS_PER_SECOND} (one billion), for which {@code
 * MAX_TICKS_PER_SECOND % ticksPerSecond == 0}. That restriction is what makes one tick equal to
 * exactly {@code MAX_TICKS_PER_SECOND / ticksPerSecond} nanoseconds, an exact integer, so every
 * tick &harr; TAI conversion is exact in {@link MissionInstant}'s nanosecond representation with
 * no rounding or floating-point drift anywhere. A real spacecraft clock would not get this
 * courtesy; this is a named SIMULATION convenience, stated as such in {@code environment}.
 *
 * <p><b>Validity interval.</b> {@link Correlation#validInterval()} is a half-open {@code
 * [start, end)} {@link TimeWindow} expressed in TAI: the continuous physical timeline over which
 * this specific correlation function is asserted to hold. Both conversion directions reject a
 * request whose resulting or input TAI instant falls outside it, rather than extrapolating.
 */
public final class SimulationTimeCorrelationContracts {
  private SimulationTimeCorrelationContracts() {}

  /**
   * One published SIMULATION tick &harr; TAI correlation for one spacecraft.
   *
   * <p>{@code timeCorrelationId} is bound, byte-for-byte, against the spacecraft's currently
   * stored {@code CatalogContracts.MissionProfile#timeCorrelationId()} at publish time (see
   * {@code SimulationTimeCorrelationApi.publish}), exactly as {@code missionDefinitionVersion} is
   * bound against {@code MissionProfile#missionDefinitionVersion()} everywhere else in this
   * package. {@code clockPartition} must match {@code msc.domain.time.OnboardTime#clockPartition()}
   * for any onboard time value this correlation is used to convert.
   *
   * <p>{@code tickEpoch} is the onboard tick count, and {@code taiEpoch} the TAI instant, of the
   * same physical moment — the anchor the whole affine mapping is built from. {@code tickEpoch}
   * must be non-negative, matching {@code OnboardTime}'s own invariant that a tick count is never
   * negative.
   */
  public record Correlation(
      String spacecraftId,
      String missionDefinitionVersion,
      String timeCorrelationId,
      String clockPartition,
      MissionInstant taiEpoch,
      long tickEpoch,
      long ticksPerSecond,
      TimeWindow validInterval,
      String environment,
      String approvalReference,
      String provenance) {

    /** One second, in nanoseconds. Also the upper bound on {@link #ticksPerSecond}. */
    public static final long MAX_TICKS_PER_SECOND = 1_000_000_000L;

    public Correlation {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(timeCorrelationId);
      text(clockPartition);
      Objects.requireNonNull(taiEpoch, "TAI epoch is required");
      taiEpoch.requireTai();
      if (tickEpoch < 0)
        throw new IllegalArgumentException("Tick epoch must not be negative");
      if (ticksPerSecond <= 0
          || ticksPerSecond > MAX_TICKS_PER_SECOND
          || MAX_TICKS_PER_SECOND % ticksPerSecond != 0)
        throw new IllegalArgumentException(
            "ticksPerSecond must be a positive divisor of "
                + MAX_TICKS_PER_SECOND
                + " (an explicit SIMULATION representation choice, not a hardware limit)");
      Objects.requireNonNull(validInterval, "Valid interval is required");
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException(
            "Only explicitly simulated time correlations are supported");
      text(approvalReference);
      text(provenance);
    }

    /** Exact nanoseconds per tick; exact because {@link #ticksPerSecond} evenly divides 1e9. */
    public long nanosPerTick() {
      return MAX_TICKS_PER_SECOND / ticksPerSecond;
    }

    private void requireMatch(String requestedTimeCorrelationId, String requestedClockPartition) {
      Objects.requireNonNull(requestedTimeCorrelationId, "Time correlation identity is required");
      Objects.requireNonNull(requestedClockPartition, "Clock partition is required");
      if (!timeCorrelationId.equals(requestedTimeCorrelationId))
        throw new IllegalArgumentException(
            "Time correlation identity mismatch: this correlation is "
                + timeCorrelationId
                + ", requested "
                + requestedTimeCorrelationId);
      if (!clockPartition.equals(requestedClockPartition))
        throw new IllegalArgumentException(
            "Clock partition mismatch: this correlation is for "
                + clockPartition
                + ", requested "
                + requestedClockPartition);
    }

    private boolean withinValidity(MissionInstant instant) {
      return instant.compareTo(validInterval.start()) >= 0
          && instant.compareTo(validInterval.end()) < 0;
    }

    /**
     * Converts an onboard tick count to TAI. {@code elapsedTicks = tick - tickEpoch} may be
     * negative (a tick before the epoch). The nanosecond offset ({@code elapsedTicks *
     * nanosPerTick()}) is added to {@code taiEpoch} with correct carry/borrow across the second
     * boundary using {@link Math#floorDiv(long, long)} / {@link Math#floorMod(long, long)}, which
     * -- unlike truncating division/remainder -- produce the mathematically correct
     * always-nonnegative fractional remainder for a negative numerator. All arithmetic uses
     * {@link Math}'s checked ({@code *Exact}) operators; any overflow throws {@link
     * ArithmeticException} rather than silently wrapping.
     *
     * @param requestedTimeCorrelationId caller's expected {@link #timeCorrelationId()}; a
     *     mismatch is rejected rather than silently converted under this correlation.
     * @param requestedClockPartition caller's expected {@link #clockPartition()}, mirroring
     *     {@code OnboardTime#clockPartition()}; a mismatch is rejected.
     * @throws IllegalArgumentException on a negative input tick, an identity/partition mismatch,
     *     or a result outside {@link #validInterval()}. A negative tick is rejected explicitly,
     *     before any arithmetic, independent of the validity-window check: {@code
     *     msc.domain.time.OnboardTime}'s own compact constructor already forbids a negative tick
     *     count, so a correlation that converted one anyway would hand back a TAI instant for an
     *     onboard time that can never exist. This must not be left to the overflow guard or the
     *     validity-window check, either of which could otherwise miss it — for example, a
     *     positive {@code tickEpoch} paired with a broad valid interval lets {@code tick = -1}
     *     both avoid overflow and land inside the window.
     * @throws ArithmeticException on overflow in the underlying checked arithmetic.
     */
    public MissionInstant tickToTai(
        String requestedTimeCorrelationId, String requestedClockPartition, long tick) {
      requireMatch(requestedTimeCorrelationId, requestedClockPartition);
      if (tick < 0)
        throw new IllegalArgumentException("Tick must not be negative: " + tick);
      long elapsedTicks = Math.subtractExact(tick, tickEpoch);
      long totalNanos = Math.multiplyExact(elapsedTicks, nanosPerTick());
      long carrySeconds = Math.floorDiv(totalNanos, MAX_TICKS_PER_SECOND);
      int fractionNanos = (int) Math.floorMod(totalNanos, MAX_TICKS_PER_SECOND);
      long seconds = Math.addExact(taiEpoch.seconds(), carrySeconds);
      int nanos = taiEpoch.nanos() + fractionNanos;
      if (nanos >= MAX_TICKS_PER_SECOND) {
        nanos -= MAX_TICKS_PER_SECOND;
        seconds = Math.addExact(seconds, 1);
      }
      var result = new MissionInstant(seconds, nanos, TimeScale.TAI);
      if (!withinValidity(result))
        throw new IllegalArgumentException(
            "Tick " + tick + " maps outside this correlation's valid interval");
      return result;
    }

    /**
     * Converts a TAI instant to an onboard tick count. Computes the exact nanosecond difference
     * from {@code taiEpoch} using checked arithmetic; if that difference is not exactly aligned
     * to the tick grid ({@code nanos % nanosPerTick() != 0}) this rejects rather than rounding to
     * the nearest tick. The exact quotient is added to {@code tickEpoch}; a negative resulting
     * tick is rejected.
     *
     * @throws IllegalArgumentException on an identity/partition mismatch, an input outside {@link
     *     #validInterval()}, tick-grid misalignment, or a negative resulting tick.
     * @throws ArithmeticException on overflow in the underlying checked arithmetic.
     */
    public long taiToTick(
        String requestedTimeCorrelationId, String requestedClockPartition, MissionInstant instant) {
      requireMatch(requestedTimeCorrelationId, requestedClockPartition);
      Objects.requireNonNull(instant, "TAI instant is required");
      instant.requireTai();
      if (!withinValidity(instant))
        throw new IllegalArgumentException(
            "TAI instant " + instant + " is outside this correlation's valid interval");
      long deltaSeconds = Math.subtractExact(instant.seconds(), taiEpoch.seconds());
      long deltaNanos = instant.nanos() - taiEpoch.nanos();
      long totalNanos = Math.addExact(Math.multiplyExact(deltaSeconds, MAX_TICKS_PER_SECOND), deltaNanos);
      long nanosPerTick = nanosPerTick();
      if (totalNanos % nanosPerTick != 0)
        throw new IllegalArgumentException(
            "TAI instant " + instant + " is not aligned to this correlation's tick grid");
      long elapsedTicks = totalNanos / nanosPerTick;
      long tick = Math.addExact(tickEpoch, elapsedTicks);
      if (tick < 0)
        throw new IllegalArgumentException("Resulting tick value is negative: " + tick);
      return tick;
    }
  }

  /** CAS publish envelope, matching {@code AgilityContracts.Publish} exactly in shape. */
  public record Publish(long expectedVersion, Correlation correlation) {
    public Publish {
      if (expectedVersion < 0 || correlation == null)
        throw new IllegalArgumentException("Expected version and correlation are required");
    }
  }
}
