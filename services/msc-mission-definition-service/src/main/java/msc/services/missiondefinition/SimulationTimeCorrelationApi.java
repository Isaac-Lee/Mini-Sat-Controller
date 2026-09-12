package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationTimeCorrelationContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Owner API for {@link Correlation}, the explicit versioned SIMULATION onboard tick &harr; TAI
 * correlation. Mirrors {@code AgilityApi} / {@code OperationResourceApi} exactly: ADMIN publish
 * under an {@code Idempotency-Key} with CAS on {@code expectedVersion}, an immutable version
 * history, and one outbox event per successful publish, all in one transaction; reads of the
 * current and any exact historical version for ADMIN/OPERATOR/SERVICE under both {@code /api} and
 * {@code /internal}, so a future Control service and a future Simulator can pin an exact version
 * rather than resolving "latest".
 *
 * <p><b>Scope.</b> This class is Slice A only: it publishes and serves the correlation record.
 * {@link Correlation#tickToTai} and {@link Correlation#taiToTick} are pure conversion methods on
 * the contract itself, invoked by a caller that has already resolved the exact pinned version it
 * needs (via {@link #current} or {@link #version}) — this class deliberately exposes no HTTP
 * conversion endpoint of its own. There is no command execution, no onboard ledger, and no
 * physical effect implemented anywhere in this class or file.
 */
@RestController
public class SimulationTimeCorrelationApi {
  private final StateStore store;

  public SimulationTimeCorrelationApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/simulation-time-correlations")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-time-correlation:" + actor.getName(),
        key,
        request,
        () -> {
          var correlation = request.correlation();
          String id = correlation.spacecraftId();
          store.lock("simulation-time-correlation:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(correlation.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Simulation time correlation must bind the configured mission definition");
          if (!mission.timeCorrelationId().equals(correlation.timeCorrelationId()))
            throw ApiException.invalid(
                "Simulation time correlation must bind the mission's configured time correlation"
                    + " identity");
          var old = store.find("simulation-time-correlation", id, Correlation.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Simulation time correlation version changed");
          var saved =
              old.isEmpty()
                  ? store.create("simulation-time-correlation", id, correlation)
                  : store.update(
                      "simulation-time-correlation", id, request.expectedVersion(), correlation);
          store.event(
              "SimulationTimeCorrelationPublished",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({
    "/api/simulation-time-correlations/{id}",
    "/internal/simulation-time-correlations/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Correlation> current(@PathVariable String id) {
    return store.require("simulation-time-correlation", id, Correlation.class);
  }

  @GetMapping({
    "/api/simulation-time-correlations/{id}/versions/{version}",
    "/internal/simulation-time-correlations/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Correlation> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("simulation-time-correlation", id, version, Correlation.class)
        .orElseThrow(() -> ApiException.missing("Simulation time correlation version not found"));
  }
}
