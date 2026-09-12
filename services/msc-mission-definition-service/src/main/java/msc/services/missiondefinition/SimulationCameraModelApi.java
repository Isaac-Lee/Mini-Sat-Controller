package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.SimulationCameraModelContracts.*;
import msc.contracts.SimulationPlanningContracts;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * ADMIN-published, versioned SIMULATION camera model for a spacecraft. Mirrors {@code
 * SimulationPlanningModelApi}/{@code AgilityApi} in shape and validation style.
 *
 * <p>Publish-time validation binds the model to two independently resolved, currently stored
 * documents, never to caller-supplied geometry:
 *
 * <ul>
 *   <li>{@code mission} ({@code CatalogContracts.MissionProfile}), exactly as {@code AgilityApi}
 *       and {@code SimulationPlanningModelApi} bind it, via {@code missionDefinitionVersion}
 *       equality.
 *   <li>The spacecraft's current {@code SimulationPlanningContracts.Model}, via an exact stored
 *       version match against {@link
 *       SimulationCameraModelContracts.Model#simulationPlanningModelVersion()}, and a {@code
 *       maximumOffNadirDegrees} bound check: the camera model's own bound must not exceed the
 *       pinned planning model's bound.
 * </ul>
 *
 * <p>This API publishes the camera model only. It computes no footprint, no ground sample distance
 * and no coverage; {@code AOI_SENSOR_COVERAGE} is not evaluated here or anywhere else in this
 * codebase yet.
 */
@RestController
public class SimulationCameraModelApi {
  private final StateStore store;

  public SimulationCameraModelApi(StateStore store) {
    this.store = store;
  }

  @PostMapping("/api/simulation-camera-models")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-camera-model:" + actor.getName(),
        key,
        request,
        () -> {
          var model = request.model();
          String id = model.spacecraftId();
          store.lock("simulation-camera-model:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.spacecraftId().equals(id)
              || !mission.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Simulation camera model must bind the configured mission definition");
          var planningModel =
              store.require(
                  "simulation-planning-model", id, SimulationPlanningContracts.Model.class);
          if (!planningModel.body().spacecraftId().equals(id)
              || !planningModel
                  .body()
                  .missionDefinitionVersion()
                  .equals(model.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Pinned planning model must bind the same spacecraft and mission definition");
          if (planningModel.version() != model.simulationPlanningModelVersion())
            throw ApiException.invalid(
                "Simulation camera model must pin the current simulation planning model version");
          if (model.maximumOffNadirDegrees() > planningModel.body().maximumOffNadirDegrees())
            throw ApiException.invalid(
                "Camera maximum off-nadir bound cannot exceed the pinned simulation planning"
                    + " model's maximum off-nadir bound");
          var old = store.find("simulation-camera-model", id, Model.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Simulation camera model version changed");
          var saved =
              old.isEmpty()
                  ? store.create("simulation-camera-model", id, model)
                  : store.update("simulation-camera-model", id, request.expectedVersion(), model);
          store.event(
              "SimulationCameraModelPublished",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({"/api/simulation-camera-models/{id}", "/internal/simulation-camera-models/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> current(@PathVariable String id) {
    return store.require("simulation-camera-model", id, Model.class);
  }

  @GetMapping({
    "/api/simulation-camera-models/{id}/versions/{version}",
    "/internal/simulation-camera-models/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Model> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("simulation-camera-model", id, version, Model.class)
        .orElseThrow(() -> ApiException.missing("Simulation camera model version not found"));
  }
}
