package msc.orbit;

import java.util.ArrayList;
import msc.domain.flightdynamics.*;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import org.orekit.propagation.analytical.tle.*;

/** Uses GP mean elements directly; never converts a TLE epoch to a two-body initial orbit. */
public final class GeneralPerturbationsAdapter {
  public static final String MODEL = "orekit-13.1.8-SGP4-SDP4-public-GP";
  private final OrekitReferenceFrames references;
  private final KeplerianOrbitAdapter time = new KeplerianOrbitAdapter();

  public GeneralPerturbationsAdapter(OrekitReferenceFrames references) {
    this.references = references;
  }

  public MissionInstant epoch(MeanElements e) {
    return references.fromUtc(e.epochUtc());
  }

  TLE tle(MeanElements e) {
    String designator = e.internationalDesignator();
    int year = designator.isEmpty() ? 0 : Integer.parseInt(designator.substring(0, 4));
    int launch = designator.isEmpty() ? 0 : Integer.parseInt(designator.substring(5, 8));
    String piece = designator.isEmpty() ? "" : designator.substring(8);
    double day = 86400, twoPi = 2 * Math.PI;
    // GP DOT and DDOT fields encode n-dot/2 and n-double-dot/6 in rev/day powers.
    return new TLE(
        e.noradId(),
        e.classification().charAt(0),
        year,
        launch,
        piece,
        0,
        e.elementSetNumber(),
        time.date(epoch(e)),
        e.meanMotionRevolutionsPerDay() * twoPi / day,
        e.meanMotionDot() * 2 * twoPi / (day * day),
        e.meanMotionDdot() * 6 * twoPi / (day * day * day),
        e.eccentricity(),
        Math.toRadians(e.inclinationDegrees()),
        Math.toRadians(e.pericenterDegrees()),
        Math.toRadians(e.ascendingNodeDegrees()),
        Math.toRadians(e.meanAnomalyDegrees()),
        e.revolutionNumber(),
        e.bstar(),
        references.context().getTimeScales().getUTC());
  }

  TLEPropagator propagator(MeanElements e) {
    return TLEPropagator.selectExtrapolator(tle(e), references.context().getFrames().getTEME());
  }

  public Prediction predict(
      String solutionId, MeanElements e, TimeWindow horizon, int stepSeconds) {
    double span = time.date(horizon.end()).durationFrom(time.date(horizon.start()));
    if (stepSeconds < 1
        || stepSeconds > 3600
        || span > 7 * 86400
        || Math.ceil(span / stepSeconds) > 20000)
      throw new IllegalArgumentException(
          "Prediction must fit seven days and 20000 samples with step 1..3600 seconds");
    for (var instant : new MissionInstant[] {horizon.start(), horizon.end()})
      if (Math.abs(time.date(instant).durationFrom(time.date(epoch(e)))) > 7 * 86400)
        throw new IllegalArgumentException(
            "Public GP epoch is more than seven days from prediction; refresh reference");
    var propagator = propagator(e);
    var samples = new ArrayList<Sample>();
    for (var instant = horizon.start();
        instant.compareTo(horizon.end()) < 0;
        instant = instant.plus(new MissionDuration(stepSeconds * 1_000_000_000L))) {
      var state = propagator.propagate(time.date(instant));
      // TEME is not EME2000: explicitly transform both position and velocity for shared API
      // consumers.
      var pv = state.getPVCoordinates(references.context().getFrames().getEME2000());
      samples.add(
          new Sample(
              instant,
              KeplerianOrbitAdapter.vector(pv.getPosition()),
              KeplerianOrbitAdapter.vector(pv.getVelocity())));
    }
    return new Prediction(solutionId, MODEL, "EME2000", horizon, stepSeconds, samples);
  }

  public AccessPrediction access(
      String solutionId, String spacecraftId, MeanElements e, AccessPrediction.Query query) {
    return new OrekitAccessPredictor(references)
        .predict(solutionId, spacecraftId, epoch(e), MODEL, propagator(e), query);
  }

  /**
   * Finite-Sun, penumbra-inclusive eclipse/sunlit windows for a real SGP4/SDP4 propagation of
   * these GP mean elements, shaped exactly like {@link #access}: delegates to {@link
   * OrekitIlluminationPredictor}'s propagator-agnostic overload using the real {@link
   * #propagator(MeanElements)} (TEME) directly. Never converts these mean elements into a
   * two-body {@link msc.domain.flightdynamics.Trajectory.InitialState} -- see the class javadoc.
   */
  public OrekitIlluminationPredictor.SpacecraftEclipse eclipse(MeanElements e, TimeWindow horizon) {
    return new OrekitIlluminationPredictor(references).spacecraftEclipse(epoch(e), propagator(e), horizon);
  }
}
