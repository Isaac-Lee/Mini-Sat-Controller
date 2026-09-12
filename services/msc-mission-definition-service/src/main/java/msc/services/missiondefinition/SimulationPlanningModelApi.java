package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationPlanningContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit ADMIN-published, versioned simulation planning model bound to the stored mission
 * definition. Supplies mission phase/mode and simulation-only sensor/power assumptions that later
 * full feasibility evaluation needs; sensor feasibility itself is not evaluated here or wired
 * into Planning.
 */
@RestController
public class SimulationPlanningModelApi {
  private final StateStore store;

  public SimulationPlanningModelApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/simulation-planning-models")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-planning-model:" + actor.getName(),
        key,
        request,
        () -> {
          var model = request.model();
          String id = model.spacecraftId();
          store.lock("simulation-planning-model:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Simulation planning model must bind the configured mission definition");
          if (model.minimumPropellantKg() > mission.propellantKg())
            throw ApiException.invalid(
                "Minimum propellant reserve cannot exceed the mission's propellant load");
          var old = store.find("simulation-planning-model", id, Model.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Simulation planning model version changed");
          var saved =
              old.isEmpty()
                  ? store.create("simulation-planning-model", id, model)
                  : store.update("simulation-planning-model", id, request.expectedVersion(), model);
          store.event(
              "SimulationPlanningModelPublished",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({
    "/api/simulation-planning-models/{id}",
    "/internal/simulation-planning-models/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> current(@PathVariable String id) {
    return store.require("simulation-planning-model", id, Model.class);
  }

  @GetMapping({
    "/api/simulation-planning-models/{id}/versions/{version}",
    "/internal/simulation-planning-models/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("simulation-planning-model", id, version, Model.class)
        .orElseThrow(() -> ApiException.missing("Simulation planning model version not found"));
  }
}
