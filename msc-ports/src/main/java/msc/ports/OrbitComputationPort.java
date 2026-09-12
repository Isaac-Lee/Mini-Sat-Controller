package msc.ports;

import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.flightdynamics.Trajectory.Prediction;
import msc.domain.time.TimeWindow;

/** Numerical prediction from an immutable EME2000 Cartesian solution in SI units. */
public interface OrbitComputationPort {
  Prediction predict(InitialState initial, TimeWindow horizon, int stepSeconds);
}
