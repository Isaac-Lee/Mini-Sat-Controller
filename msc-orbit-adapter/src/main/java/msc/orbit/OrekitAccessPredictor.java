package msc.orbit;

import java.util.ArrayList;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.flightdynamics.AccessPrediction.*;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.time.*;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.FunctionalDetector;
import org.orekit.propagation.events.handlers.ContinueOnEvent;

/** Continuous Orekit event roots with explicit scan/tolerance and radial-nadir pointing model. */
public final class OrekitAccessPredictor {
  private final OrekitReferenceFrames references;
  private final KeplerianOrbitAdapter time = new KeplerianOrbitAdapter();

  public OrekitAccessPredictor(OrekitReferenceFrames references) {
    this.references = references;
  }

  public AccessPrediction predict(InitialState initial, Query query) {
    return predict(
        initial.solutionId(),
        initial.spacecraftId(),
        initial.epoch(),
        KeplerianOrbitAdapter.MODEL,
        time.propagator(initial),
        query);
  }

  AccessPrediction predict(
      String solutionId,
      String spacecraftId,
      MissionInstant epoch,
      String model,
      org.orekit.propagation.Propagator propagator,
      Query query) {
    var start = time.date(query.horizon().start());
    var end = time.date(query.horizon().end());
    double duration = end.durationFrom(start);
    if (duration > 86400)
      throw new IllegalArgumentException("Access search is limited to 24 hours per request");
    if (Math.abs(start.durationFrom(time.date(epoch))) > 7 * 86400
        || Math.abs(end.durationFrom(time.date(epoch))) > 7 * 86400)
      throw new IllegalArgumentException("Initial orbit is outside model validity interval");
    references.requireCoverage(start);
    references.requireCoverage(end);
    var earth = references.earth();
    var target = query.target();
    var geodetic =
        new GeodeticPoint(
            Math.toRadians(target.latitudeDegrees()),
            Math.toRadians(target.longitudeDegrees()),
            target.altitudeMeters());
    var station = new TopocentricFrame(earth, geodetic, target.id());
    var targetPosition = earth.transform(geodetic);
    double check = Math.min(5.0, query.minimumDurationSeconds() / 2.0);
    double tolerance = 0.001;
    var detector =
        new FunctionalDetector()
            .withMaxCheck(check)
            .withThreshold(tolerance)
            .withMaxIter(100)
            .withHandler(new ContinueOnEvent())
            .withFunction(
                state -> {
                  var position = state.getPosition(earth.getBodyFrame());
                  double elevation =
                      station.getElevation(position, earth.getBodyFrame(), state.getDate());
                  double margin = elevation - Math.toRadians(query.minimumElevationDegrees());
                  if (query.kind() == Kind.POINT_IMAGING) {
                    double offNadir =
                        Vector3D.angle(position.negate(), targetPosition.subtract(position));
                    margin =
                        Math.min(margin, Math.toRadians(query.maximumOffNadirDegrees()) - offNadir);
                  }
                  return margin;
                });
    var atStart = propagator.propagate(start);
    var windows = new ArrayList<TimeWindow>();
    MissionInstant opening = detector.g(atStart) >= 0 ? query.horizon().start() : null;
    var logger = new EventsLogger();
    propagator.addEventDetector(logger.monitorDetector(detector));
    propagator.propagate(start, end);
    for (var event : logger.getLoggedEvents()) {
      var instant = time.instant(event.getState().getDate());
      if (instant.compareTo(query.horizon().start()) < 0
          || instant.compareTo(query.horizon().end()) > 0) continue;
      if (event.isIncreasing()) {
        if (opening == null) opening = instant;
      } else if (opening != null) {
        append(windows, opening, instant, query.minimumDurationSeconds());
        opening = null;
      }
    }
    if (opening != null)
      append(windows, opening, query.horizon().end(), query.minimumDurationSeconds());
    return new AccessPrediction(
        solutionId,
        spacecraftId,
        model + "/WGS84-radial-nadir-point-access",
        references.digest(),
        query,
        tolerance,
        check,
        windows);
  }

  private void append(
      ArrayList<TimeWindow> windows, MissionInstant start, MissionInstant end, int minimum) {
    if (start.compareTo(end) < 0 && time.date(end).durationFrom(time.date(start)) >= minimum)
      windows.add(new TimeWindow(start, end));
  }
}
