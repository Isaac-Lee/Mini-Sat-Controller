package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import msc.domain.time.TimeWindow;

/**
 * Target (ground-point) sun-elevation illumination and spacecraft eclipse/sunlit contracts,
 * produced by {@code msc.orbit.OrekitIlluminationPredictor} and served by the Flight Dynamics
 * illumination API.
 *
 * <p><b>Accuracy is never implied here.</b> Every result carries an explicit {@code solarModel}
 * id and {@code solarModelAccuracyNote} string. The analytical solar model behind it is
 * materially less accurate than a JPL/INPOP ephemeris and no sourced numeric accuracy bound is
 * available in this build environment; treat the note as an uncharacterised, assumed simulation
 * margin, never a mission-qualified bound. See {@code docs/backend/illumination.md}.
 *
 * <p><b>Target illumination is evaluated at five sampled points only</b> (AOI centre plus its
 * four corners), never proven over the continuous AOI area. A dark patch between the samples is
 * undetectable, so the intersection of all five points' illuminated windows is an
 * OVER-approximation of full-AOI illumination (a superset of the true full-AOI-illuminated
 * times), not a lower bound. {@link AoiIlluminationScope#SAMPLED_POINTS_ONLY} is the only scope
 * this contract can ever produce; full-AOI illumination remains NOT_EVALUATED and must be treated
 * as such by every consumer. {@link TargetIlluminationResult} enforces exactly five points at
 * construction, so a centre-only (or any non-five-point) sample can never be represented as an
 * AOI-level result at all.
 */
public final class IlluminationContracts {
  private IlluminationContracts() {}

  /** Degrees of latitude within which an AOI edge is rejected as pole-containing/approaching. */
  public static final double POLE_GUARD_DEGREES = 89.0;

  public record GroundPoint(
      String id, double latitudeDegrees, double longitudeDegrees, double altitudeMeters) {
    public GroundPoint {
      text(id);
      if (!Double.isFinite(latitudeDegrees) || Math.abs(latitudeDegrees) > 90)
        throw new IllegalArgumentException("Invalid latitude");
      if (!Double.isFinite(longitudeDegrees) || Math.abs(longitudeDegrees) > 180)
        throw new IllegalArgumentException("Invalid longitude");
      if (!Double.isFinite(altitudeMeters) || altitudeMeters < -500 || altitudeMeters > 10000)
        throw new IllegalArgumentException("Invalid altitude");
    }
  }

  /**
   * Axis-aligned lat/lon box. West must be strictly less than east (an antimeridian-crossing box
   * expressed as west&gt;east is rejected explicitly rather than mis-centred or wrapped), and
   * neither edge may approach a pole (|latitude| &gt; {@link #POLE_GUARD_DEGREES}), since corner
   * sampling and topocentric frames are degenerate there.
   */
  public record Aoi(
      String id,
      double westLongitudeDegrees,
      double eastLongitudeDegrees,
      double southLatitudeDegrees,
      double northLatitudeDegrees,
      double altitudeMeters) {
    public Aoi {
      text(id);
      if (!Double.isFinite(westLongitudeDegrees) || Math.abs(westLongitudeDegrees) > 180)
        throw new IllegalArgumentException("Invalid west longitude");
      if (!Double.isFinite(eastLongitudeDegrees) || Math.abs(eastLongitudeDegrees) > 180)
        throw new IllegalArgumentException("Invalid east longitude");
      if (westLongitudeDegrees >= eastLongitudeDegrees)
        throw new IllegalArgumentException(
            "AOI must not cross the antimeridian: west longitude must be strictly less than east"
                + " longitude");
      if (!Double.isFinite(southLatitudeDegrees) || Math.abs(southLatitudeDegrees) > 90)
        throw new IllegalArgumentException("Invalid south latitude");
      if (!Double.isFinite(northLatitudeDegrees) || Math.abs(northLatitudeDegrees) > 90)
        throw new IllegalArgumentException("Invalid north latitude");
      if (southLatitudeDegrees >= northLatitudeDegrees)
        throw new IllegalArgumentException(
            "AOI south latitude must be strictly less than north latitude");
      if (Math.abs(southLatitudeDegrees) > POLE_GUARD_DEGREES
          || Math.abs(northLatitudeDegrees) > POLE_GUARD_DEGREES)
        throw new IllegalArgumentException(
            "AOI must not contain or approach a pole (|latitude| > "
                + POLE_GUARD_DEGREES
                + " degrees)");
      if (!Double.isFinite(altitudeMeters) || altitudeMeters < -500 || altitudeMeters > 10000)
        throw new IllegalArgumentException("Invalid altitude");
    }

    /** Centre plus the four corners, in a fixed order: centre, NW, NE, SW, SE. */
    public List<GroundPoint> fivePointSample() {
      double midLat = (southLatitudeDegrees + northLatitudeDegrees) / 2.0;
      double midLon = (westLongitudeDegrees + eastLongitudeDegrees) / 2.0;
      return List.of(
          new GroundPoint(id + ":centre", midLat, midLon, altitudeMeters),
          new GroundPoint(id + ":nw", northLatitudeDegrees, westLongitudeDegrees, altitudeMeters),
          new GroundPoint(id + ":ne", northLatitudeDegrees, eastLongitudeDegrees, altitudeMeters),
          new GroundPoint(id + ":sw", southLatitudeDegrees, westLongitudeDegrees, altitudeMeters),
          new GroundPoint(id + ":se", southLatitudeDegrees, eastLongitudeDegrees, altitudeMeters));
    }
  }

  public record TargetIlluminationQuery(
      Aoi aoi, TimeWindow horizon, double minimumSunElevationDegrees) {
    public TargetIlluminationQuery {
      Objects.requireNonNull(aoi);
      Objects.requireNonNull(horizon);
      if (!Double.isFinite(minimumSunElevationDegrees)
          || minimumSunElevationDegrees < -90
          || minimumSunElevationDegrees >= 90)
        throw new IllegalArgumentException("Invalid minimum sun elevation bound");
    }
  }

  public record SampledPointIllumination(
      String pointId,
      double latitudeDegrees,
      double longitudeDegrees,
      List<TimeWindow> illuminatedWindows) {
    public SampledPointIllumination {
      text(pointId);
      illuminatedWindows = List.copyOf(illuminatedWindows);
    }
  }

  /**
   * The only scope value this contract can ever produce. There is deliberately no
   * FULL_AOI_PROVEN (or any other) value: five-point sampling can only ever describe what was
   * sampled, never the continuous AOI.
   */
  public enum AoiIlluminationScope {
    SAMPLED_POINTS_ONLY
  }

  public record TargetIlluminationResult(
      String spacecraftId,
      String solarModel,
      String solarModelAccuracyNote,
      String referenceDigest,
      TargetIlluminationQuery query,
      double rootToleranceSeconds,
      double maximumCheckSeconds,
      List<SampledPointIllumination> points,
      List<TimeWindow> sampledPointsIntersection,
      AoiIlluminationScope scope) {
    public TargetIlluminationResult {
      text(spacecraftId);
      text(solarModel);
      text(solarModelAccuracyNote);
      text(referenceDigest);
      Objects.requireNonNull(query);
      points = List.copyOf(points);
      if (points.size() != 5)
        throw new IllegalArgumentException(
            "Exactly five sampled points (centre + four corners) are required; a partial sample"
                + " can never be stored as an AOI-level result");
      sampledPointsIntersection = List.copyOf(sampledPointsIntersection);
      Objects.requireNonNull(scope);
    }
  }

  /**
   * {@code orbitPropagationModel} identifies the source-orbit propagation model used to derive
   * the spacecraft trajectory this eclipse/sunlit result was computed from -- e.g. {@code
   * msc.orbit.KeplerianOrbitAdapter#MODEL} for a Cartesian two-body input or {@code
   * msc.orbit.GeneralPerturbationsAdapter#MODEL} for a real SGP4/SDP4 public-GP input. It is
   * distinct from {@link #eclipseModel}, which names the eclipse/shadow geometry model and is the
   * same for every orbit kind.
   *
   * <p>{@link Optional#empty()} means <b>legacy, unbound</b>: the result predates this field and
   * its true source propagation model was never recorded. Empty must NEVER be produced by a new
   * write and must never be read as "GP" or as any other qualified default -- an empty value
   * carries no information about which model was actually used. A missing JSON property (every
   * historical stored row) decodes to {@link Optional#empty()}, and a literal {@code null} is
   * normalized to {@link Optional#empty()} in the compact constructor below, so every historical
   * {@code GET} keeps working exactly as before this field existed.
   */
  public record SpacecraftEclipseResult(
      String solutionId,
      String spacecraftId,
      String eclipseModel,
      String solarModelAccuracyNote,
      String referenceDigest,
      TimeWindow horizon,
      double rootToleranceSeconds,
      double maximumCheckSeconds,
      List<TimeWindow> sunlitWindows,
      List<TimeWindow> eclipseWindows,
      Optional<String> orbitPropagationModel) {
    public SpacecraftEclipseResult {
      text(solutionId);
      text(spacecraftId);
      text(eclipseModel);
      text(solarModelAccuracyNote);
      text(referenceDigest);
      Objects.requireNonNull(horizon);
      sunlitWindows = List.copyOf(sunlitWindows);
      eclipseWindows = List.copyOf(eclipseWindows);
      // A missing/legacy field must decode to empty, never throw and never be misread as bound.
      if (orbitPropagationModel == null) orbitPropagationModel = Optional.empty();
      orbitPropagationModel.ifPresent(msc.domain.shared.Checks::text);
    }

    /**
     * Preserves the exact pre-existing ten-argument constructor so every caller and every
     * historical stored record written before {@link #orbitPropagationModel} existed keeps
     * working unchanged; binds an explicitly empty (legacy, unbound) model. No production caller
     * should use this overload going forward -- every new write must pass a populated model
     * through the canonical constructor above.
     */
    public SpacecraftEclipseResult(
        String solutionId,
        String spacecraftId,
        String eclipseModel,
        String solarModelAccuracyNote,
        String referenceDigest,
        TimeWindow horizon,
        double rootToleranceSeconds,
        double maximumCheckSeconds,
        List<TimeWindow> sunlitWindows,
        List<TimeWindow> eclipseWindows) {
      this(
          solutionId,
          spacecraftId,
          eclipseModel,
          solarModelAccuracyNote,
          referenceDigest,
          horizon,
          rootToleranceSeconds,
          maximumCheckSeconds,
          sunlitWindows,
          eclipseWindows,
          Optional.empty());
    }
  }

  /**
   * The intersection of every list of (ordered, non-overlapping) windows in {@code
   * perPointWindows}, ordered by start time. With zero input lists, returns an empty list (never
   * "always illuminated"). This is the sole aggregation this contract performs; see the class
   * javadoc for why it is an over-approximation, not a lower bound, of full-AOI illumination.
   */
  public static List<TimeWindow> intersectAll(List<List<TimeWindow>> perPointWindows) {
    if (perPointWindows.isEmpty()) return List.of();
    List<TimeWindow> acc = List.copyOf(perPointWindows.get(0));
    for (int i = 1; i < perPointWindows.size() && !acc.isEmpty(); i++)
      acc = intersectPair(acc, perPointWindows.get(i));
    acc = new ArrayList<>(acc);
    acc.sort((a, b) -> a.start().compareTo(b.start()));
    return List.copyOf(acc);
  }

  private static List<TimeWindow> intersectPair(List<TimeWindow> a, List<TimeWindow> b) {
    var result = new ArrayList<TimeWindow>();
    for (var wa : a)
      for (var wb : b) {
        var start = wa.start().compareTo(wb.start()) >= 0 ? wa.start() : wb.start();
        var end = wa.end().compareTo(wb.end()) <= 0 ? wa.end() : wb.end();
        if (start.compareTo(end) < 0) result.add(new TimeWindow(start, end));
      }
    return result;
  }
}
