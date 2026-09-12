package msc.services.planning;

import java.math.*;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.domain.anomaly.MissionPhase;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.planning.FeasibilityEvaluation;
import msc.domain.time.*;

/** Activity-sized options for further evaluation; neither PlanningRun nor committed assignment. */
final class PlanningOptions {
  record Option(
      String spacecraftId,
      String definitionId,
      long definitionVersion,
      TimeWindow window,
      MissionPhase phase,
      String mode,
      FeasibilityEvaluation.Status feasibility,
      List<String> remainingGates) {
    Option {
      remainingGates = List.copyOf(remainingGates);
    }
  }

  static List<Option> derive(
      AccessPrediction prediction, CatalogEntry catalog, Model model, TimeWindow futureHorizon) {
    if (!prediction.spacecraftId().equals(model.spacecraftId()))
      throw new IllegalArgumentException("Option spacecraft mismatch");
    catalog.activity().requireSchedulable(model.phase(), model.mode());
    if (prediction.query().maximumOffNadirDegrees() > model.maximumOffNadirDegrees())
      throw new IllegalArgumentException("Geometry exceeds sensor bound");
    var duration =
        new MissionDuration(
            BigDecimal.valueOf(catalog.durationSeconds())
                .movePointRight(9)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact());
    var options = new ArrayList<Option>();
    for (var window : prediction.windows()) {
      var start =
          window.start().compareTo(futureHorizon.start()) < 0
              ? futureHorizon.start()
              : window.start();
      var end = start.plus(duration);
      if (end.compareTo(window.end()) > 0 || end.compareTo(futureHorizon.end()) > 0) continue;
      options.add(
          new Option(
              model.spacecraftId(),
              catalog.activity().id().value(),
              catalog.activity().version(),
              new TimeWindow(start, end),
              model.phase(),
              model.mode(),
              FeasibilityEvaluation.Status.NOT_EVALUATED,
              List.of(
                  "AOI_SENSOR_COVERAGE",
                  "ILLUMINATION",
                  "ATTITUDE_SEQUENCE",
                  "RESOURCE_TIMELINE",
                  "GROUND_RESERVATION",
                  "CURRENT_MODE_AND_SAFETY",
                  "SCHEDULE_CONFLICTS")));
    }
    return List.copyOf(options);
  }
}
