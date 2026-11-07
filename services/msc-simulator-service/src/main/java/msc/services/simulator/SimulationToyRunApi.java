package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.time.TimeWindow;
import msc.platform.*;
import msc.services.simulator.SimulationCommandApi.Entry;
import msc.services.simulator.SimulationCommandApi.Ledger;
import msc.services.simulator.SimulationCommandApi.Status;
import msc.services.simulator.SimulationScenarioApi.Scenario;
import msc.services.simulator.SimulationToyPhysics.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * TOY_MODEL projection of a scenario's pending loads. It records a seeded event log and emits
 * labelled simulation telemetry, but never advances the scenario clock or changes its ledger.
 */
@RestController
public class SimulationToyRunApi {
  public record Start(
      UUID runId,
      long seed,
      long toTick,
      long stepTicks,
      double initialBatteryWh,
      long telemetryBindingVersion,
      List<TimeWindow> visibility,
      double linkDropoutProbability) {
    public Start {
      Objects.requireNonNull(runId);
      visibility = List.copyOf(visibility);
    }
  }

  public record Run(
      UUID runId, String fidelity, String environment, EventLog log, List<Sample> samples) {}

  private final StateStore store;
  private final Json json;
  private final TelemetrySimulationApi telemetry;

  public SimulationToyRunApi(StateStore store, Json json, TelemetrySimulationApi telemetry) {
    this.store = store;
    this.json = json;
    this.telemetry = telemetry;
  }

  @PostMapping("/api/simulation/scenarios/{id}/toy-runs")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode start(
      @PathVariable UUID id,
      @RequestBody Start request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-toy-run:" + actor.getName(),
        key,
        request,
        () -> {
          store.lock("simulation-scenario:" + id);
          if (store.find("simulation-toy-run", request.runId().toString(), EventLog.class)
              .isPresent()) throw ApiException.conflict("Toy run already exists");
          var scenario = store.require("simulation-scenario", id.toString(), Scenario.class).body();
          long from = scenario.currentTick();
          var pending = new ArrayList<Entry>();
          for (var load : store.list("simulation-load:" + id, 500))
            for (var e : json.convert(load.body(), Ledger.class).entries())
              if (e.status() == Status.PENDING
                  && e.completionTick() > from
                  && e.completionTick() <= request.toTick()) pending.add(e);
          EventLog log;
          List<Sample> samples;
          try {
            var c = scenario.correlation().body();
            c.tickToTai(c.timeCorrelationId(), c.clockPartition(), request.toTick());
            var setup =
                new Setup(
                    scenario.mission().spacecraftId(),
                    "simulator:toy-model:" + id,
                    request.telemetryBindingVersion(),
                    scenario.mission(),
                    c,
                    from,
                    request.toTick(),
                    request.stepTicks(),
                    new State(
                        request.initialBatteryWh(),
                        scenario.reservoirs().storedMegabytes(),
                        scenario.reservoirs().propellantKilograms()),
                    pending,
                    request.visibility(),
                    request.linkDropoutProbability());
            log = SimulationToyPhysics.record(setup, request.seed());
            samples = SimulationToyPhysics.replay(log);
          } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw ApiException.invalid("Invalid toy run: " + invalid.getMessage());
          }
          store.create("simulation-toy-run", request.runId().toString(), log);
          // Frames join this transaction: the log and its telemetry commit or roll back together.
          for (var s : samples)
            telemetry.emit(s.frame(), request.runId() + ":" + s.tick(), actor);
          return new Run(
              request.runId(),
              SimulationToyPhysics.FIDELITY,
              SimulationToyPhysics.ENVIRONMENT,
              log,
              samples);
        });
  }

  /** Recomputes telemetry from the stored log only; emits nothing and reads no live state. */
  @GetMapping("/api/simulation/toy-runs/{runId}/replay")
  @PreAuthorize("hasRole('ADMIN')")
  public Run replay(@PathVariable UUID runId) {
    var log = store.require("simulation-toy-run", runId.toString(), EventLog.class).body();
    return new Run(
        runId, log.fidelity(), log.environment(), log, SimulationToyPhysics.replay(log));
  }
}
