package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.PropellantContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PropellantModelApi {
  private final StateStore store;

  public PropellantModelApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/propellant-models")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "propellant:" + actor.getName(),
        key,
        request,
        () -> {
          var model = request.model();
          String id = model.spacecraftId();
          store.lock("propellant:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Propellant model must bind the configured mission definition");
          var old = store.find("propellant-model", id, Model.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Propellant model version changed");
          var saved =
              old.isEmpty()
                  ? store.create("propellant-model", id, model)
                  : store.update("propellant-model", id, request.expectedVersion(), model);
          store.event(
              "PropellantModelPublished", id, saved.version(), UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  @GetMapping({"/api/propellant-models/{id}", "/internal/propellant-models/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> current(@PathVariable String id) {
    return store.require("propellant-model", id, Model.class);
  }

  @GetMapping({
    "/api/propellant-models/{id}/versions/{version}",
    "/internal/propellant-models/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("propellant-model", id, version, Model.class)
        .orElseThrow(() -> ApiException.missing("Propellant model version not found"));
  }
}
