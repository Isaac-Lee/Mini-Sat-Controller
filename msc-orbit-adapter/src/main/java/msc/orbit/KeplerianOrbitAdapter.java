package msc.orbit;

import java.util.ArrayList;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import msc.ports.OrbitComputationPort;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeOffset;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;

/** Explicit two-body model for simulator integration; no mission accuracy claim. */
public final class KeplerianOrbitAdapter implements OrbitComputationPort {
  public static final String MODEL = "orekit-13.1.8-keplerian-two-body-WGS84-mu";
  private final LazyLoadedDataContext context = new LazyLoadedDataContext();
  private final AbsoluteDate origin =
      new AbsoluteDate(1970, 1, 1, context.getTimeScales().getTAI());

  public AbsoluteDate date(MissionInstant time) {
    time.requireTai();
    return origin.shiftedBy(new TimeOffset(time.seconds(), (long) time.nanos() * 1_000_000_000L));
  }

  public MissionInstant instant(AbsoluteDate date) {
    var offset = date.accurateDurationFrom(origin);
    return new MissionInstant(
        offset.getSeconds(), (int) (offset.getAttoSeconds() / 1_000_000_000L), TimeScale.TAI);
  }

  public static Vector3D vector(Vector value) {
    return new Vector3D(value.x(), value.y(), value.z());
  }

  public static Vector vector(Vector3D value) {
    return new Vector(value.getX(), value.getY(), value.getZ());
  }

  KeplerianPropagator propagator(InitialState initial) {
    var orbit =
        new CartesianOrbit(
            new PVCoordinates(
                vector(initial.positionMeters()), vector(initial.velocityMetersPerSecond())),
            context.getFrames().getEME2000(),
            date(initial.epoch()),
            Constants.WGS84_EARTH_MU);
    if (orbit.getE() >= 1
        || orbit.getA() * (1 - orbit.getE()) <= Constants.WGS84_EARTH_EQUATORIAL_RADIUS)
      throw new IllegalArgumentException("Bound non-intersecting Earth orbit required");
    return new KeplerianPropagator(orbit);
  }

  @Override
  public Prediction predict(InitialState initial, TimeWindow horizon, int stepSeconds) {
    double span = date(horizon.end()).durationFrom(date(horizon.start()));
    if (stepSeconds < 1
        || stepSeconds > 3600
        || span > 7 * 86400
        || Math.ceil(span / stepSeconds) > 20000)
      throw new IllegalArgumentException(
          "Prediction must fit seven days and 20000 samples with step 1..3600 seconds");
    if (Math.abs(date(horizon.start()).durationFrom(date(initial.epoch()))) > 7 * 86400
        || Math.abs(date(horizon.end()).durationFrom(date(initial.epoch()))) > 7 * 86400)
      throw new IllegalArgumentException("Initial orbit is outside model validity interval");
    var propagator = propagator(initial);
    var samples = new ArrayList<Sample>();
    for (var time = horizon.start();
        time.compareTo(horizon.end()) < 0;
        time = time.plus(new MissionDuration(stepSeconds * 1_000_000_000L))) {
      var pv = propagator.propagate(date(time)).getPVCoordinates();
      samples.add(new Sample(time, vector(pv.getPosition()), vector(pv.getVelocity())));
    }
    return new Prediction(initial.solutionId(), MODEL, "EME2000", horizon, stepSeconds, samples);
  }
}
