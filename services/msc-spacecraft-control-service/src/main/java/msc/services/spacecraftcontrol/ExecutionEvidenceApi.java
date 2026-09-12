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
      // Retain schedule inputs for the pending preparation/release workflow instead of
      // acknowledging and silently discarding facts already routed to this service.
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
    // Command preparation/release binding has not been implemented yet. Preserve source facts
    // without promoting them to confirmed spacecraft execution or a verified command load.
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
