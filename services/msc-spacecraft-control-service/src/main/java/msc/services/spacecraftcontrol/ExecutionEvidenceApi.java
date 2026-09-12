package msc.services.spacecraftcontrol;

import java.util.UUID;
import msc.contracts.SimulationExecutionContracts.Observation;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Control-owned received evidence. No unbound simulation observation authorizes a load. */
@RestController
public class ExecutionEvidenceApi implements EventHandler {
  public enum BindingStatus {
    UNBOUND_SIMULATION_EVIDENCE
  }

  public record Evidence(
      Observation observation,
      UUID sourceEventId,
      MissionInstant eventCreatedAt,
      MissionInstant receivedAt,
      String observationSha256,
      BindingStatus bindingStatus) {}

  private final StateStore store;
  private final Json json;
  private final Clock clock;

  public ExecutionEvidenceApi(StateStore store, Json json, Clock clock) {
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public void handle(ServiceEvent event) {
    if (!event.type().equals("SpacecraftExecutionObserved")) {
      // Retain schedule inputs for audit; preparation and V1 release resolve their owner
      // snapshots directly rather than treating this event as commanding authority.
      store.create("deferred-control-input", event.eventId().toString(), event);
      return;
    }
    var observation = json.convert(event.payload(), Observation.class);
    if (!event.aggregateId().equals(observation.spacecraftId()))
      throw ApiException.invalid("Execution observation envelope spacecraft mismatch");
    event.occurredAt().requireTai();
    String id = key(observation.scenarioId(), observation.loadId());
    String hash = json.fingerprint(observation);
    store.lock("execution-evidence:" + id);
    var old = store.find("simulation-execution-evidence", id, Evidence.class);
    if (old.isPresent()) {
      if (!old.get().body().observationSha256().equals(hash))
        throw ApiException.conflict("Conflicting execution observation for scenario/load");
      return;
    }
    // Preserve received facts first. SimulationExecutionBindingApi separately verifies the
    // released load and simulator ledger before identifying request-bound modeled effects.
    store.create(
        "simulation-execution-evidence",
        id,
        new Evidence(
            observation,
            event.eventId(),
            event.occurredAt(),
            clock.now(),
            hash,
            BindingStatus.UNBOUND_SIMULATION_EVIDENCE));
  }

  private static String key(UUID scenario, String load) {
    return scenario + ":" + load;
  }

  @GetMapping({
    "/api/simulation-execution-evidence/{scenario}/{load}",
    "/internal/simulation-execution-evidence/{scenario}/{load}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Evidence> read(@PathVariable UUID scenario, @PathVariable String load) {
    return store.require("simulation-execution-evidence", key(scenario, load), Evidence.class);
  }
}
