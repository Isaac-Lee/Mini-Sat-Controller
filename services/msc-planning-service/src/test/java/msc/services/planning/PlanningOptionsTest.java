package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.domain.anomaly.MissionPhase;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.planning.FeasibilityEvaluation;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class PlanningOptionsTest {
  TimeWindow window(long start, long end) {
    return new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(end));
  }

  Model model(MissionPhase phase) {
    return new Model(
        "norad-63229",
        "v1",
        "SIMULATION",
        phase,
        "NOMINAL",
        1000,
        10,
        20,
        0,
        true,
        5,
        10,
        0,
        0,
        0,
        "simulation-test");
  }

  @Test
  void optionsUseApprovedDurationAndExcludePastAndDeadlineOverflow() {
    var fixtures = new PlanningGeometryTest();
    var catalog = fixtures.json.convert(fixtures.catalog().value(), CatalogEntry.class);
    var prediction =
        new AccessPrediction(
            "orbit",
            "norad-63229",
            "model",
            "digest",
            new AccessPrediction.Query(
                AccessPrediction.Kind.POINT_IMAGING,
                new AccessPrediction.Target("point", 36, 127, 0),
                window(900, 1200),
                0,
                20,
                10),
            .001,
            10,
            List.of(window(900, 950), window(990, 1020), window(1100, 1120)));
    var options =
        PlanningOptions.derive(
            prediction, catalog, model(MissionPhase.ROUTINE), window(1000, 1105));
    assertEquals(1, options.size());
    assertEquals(window(1000, 1010), options.getFirst().window());
    assertEquals(FeasibilityEvaluation.Status.NOT_EVALUATED, options.getFirst().feasibility());
    assertTrue(options.getFirst().remainingGates().contains("RESOURCE_TIMELINE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PlanningOptions.derive(
                prediction, catalog, model(MissionPhase.EOL), window(1000, 1105)));
  }

  @Test
  void sensorLimitNarrowsFlightDynamicsQuery() {
    var fixtures = new PlanningGeometryTest();
    fixtures.respond("norad-63229");
    var evidence =
        fixtures.geometry.search(
            "norad-63229",
            fixtures.area,
            window(1000, 2000),
            fixtures.inputs("PUBLIC_GP"),
            fixtures.catalog(),
            Optional.of(
                fixtures.evidence(Map.of("version", 1, "body", model(MissionPhase.ROUTINE)))));
    assertEquals(
        20,
        evidence
            .value()
            .path("prediction")
            .path("body")
            .path("query")
            .path("maximumOffNadirDegrees")
            .asDouble());
  }
}
