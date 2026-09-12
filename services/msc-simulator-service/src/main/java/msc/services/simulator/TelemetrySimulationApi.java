package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.monitoring.OperationalTelemetry.Frame;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit scenario evidence injection; no implied hardware readings or physical simulator model.
 */
@RestController
public class TelemetrySimulationApi {
  private final StateStore store;

  public TelemetrySimulationApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/simulation/telemetry")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode emit(
      @RequestBody Frame frame,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    if (!frame.source().startsWith("simulator:"))
      throw ApiException.invalid("Scenario telemetry must identify simulation source");
    return store.idempotent(
        "simulation-telemetry:" + actor.getName(),
        key,
        frame,
        () -> {
          store.lock("simulation-telemetry:" + frame.spacecraftId());
          String id = frame.spacecraftId() + ":" + frame.id();
          var old = store.find("simulation-telemetry", id, Frame.class);
          if (old.isPresent()) {
            if (!old.get().body().equals(frame))
              throw ApiException.conflict("Simulation frame identity conflict");
            return old.get();
          }
          var saved = store.create("simulation-telemetry", id, frame);
          store.event(
              "TelemetryReceived",
              frame.spacecraftId(),
              saved.version(),
              UUID.randomUUID(),
              null,
              frame);
          return saved;
        });
  }
}
