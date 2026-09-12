package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.AgilityContracts.*;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgilityApi {
  private final StateStore store;

  public AgilityApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/agility-models")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "agility:" + actor.getName(),
        key,
        request,
        () -> {
          var model = request.model();
          String id = model.spacecraftId();
          store.lock("agility:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
            throw ApiException.invalid("Agility model must bind the configured mission definition");
          var old = store.find("agility-model", id, Model.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Agility model version changed");
          var saved =
              old.isEmpty()
                  ? store.create("agility-model", id, model)
                  : store.update("agility-model", id, request.expectedVersion(), model);
          store.event("AgilityModelPublished", id, saved.version(), UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  @GetMapping({"/api/agility-models/{id}", "/internal/agility-models/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> current(@PathVariable String id) {
    return store.require("agility-model", id, Model.class);
  }

  @GetMapping({
    "/api/agility-models/{id}/versions/{version}",
    "/internal/agility-models/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("agility-model", id, version, Model.class)
        .orElseThrow(() -> ApiException.missing("Agility model version not found"));
  }
}
