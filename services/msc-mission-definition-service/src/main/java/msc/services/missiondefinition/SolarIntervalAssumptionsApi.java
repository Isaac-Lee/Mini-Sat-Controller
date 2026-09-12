package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.SolarIntervalContracts.*;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Mission Definition owns explicit, scoped simulation solar interval assumptions. */
@RestController
public class SolarIntervalAssumptionsApi {
  private final StateStore store;

  public SolarIntervalAssumptionsApi(StateStore store) { this.store = store; }

  @PostMapping("/api/solar-interval-assumptions")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(@RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent("solar-interval-assumptions:" + actor.getName(), key, request, () -> {
      var policy = request.assumptions();
      String id = policy.spacecraftId();
      store.lock("solar-interval-assumptions:" + id);
      var mission = store.require("mission", id, MissionProfile.class).body();
      if (!mission.missionDefinitionVersion().equals(policy.missionDefinitionVersion()))
        throw ApiException.invalid("Solar interval assumptions mission definition mismatch");
      var old = store.find("solar-interval-assumptions", id, Assumptions.class);
      if (old.map(StateStore.State::version).orElse(0L) != request.expectedVersion())
        throw ApiException.conflict("Solar interval assumptions version changed");
      var saved = old.isEmpty() ? store.create("solar-interval-assumptions", id, policy)
          : store.update("solar-interval-assumptions", id, request.expectedVersion(), policy);
      store.event("SolarIntervalAssumptionsPublished", id, saved.version(), UUID.randomUUID(), null, saved);
      return saved;
    });
  }

  @GetMapping({"/api/solar-interval-assumptions/{id}", "/internal/solar-interval-assumptions/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Assumptions> current(@PathVariable String id) {
    return store.require("solar-interval-assumptions", id, Assumptions.class);
  }

  @GetMapping({"/api/solar-interval-assumptions/{id}/versions/{version}",
      "/internal/solar-interval-assumptions/{id}/versions/{version}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Assumptions> version(@PathVariable String id, @PathVariable long version) {
    return store.version("solar-interval-assumptions", id, version, Assumptions.class)
        .orElseThrow(() -> ApiException.missing("Solar interval assumptions version not found"));
  }
}
