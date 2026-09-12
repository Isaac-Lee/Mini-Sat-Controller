package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.AuthorityContracts.*;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Mission Definition owns commanding authority. A published rule is not a load approval. */
@RestController
public class AuthorityApi {
  private final StateStore store;

  public AuthorityApi(StateStore store) { this.store = store; }

  @PostMapping("/api/authority-policies")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(@RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent("authority-policy:" + actor.getName(), key, request, () -> {
      var policy = request.policy();
      String id = policy.spacecraftId();
      store.lock("authority-policy:" + id);
      var mission = store.require("mission", id, MissionProfile.class).body();
      if (!mission.missionDefinitionVersion().equals(policy.missionDefinitionVersion()))
        throw ApiException.invalid("Authority policy mission definition mismatch");
      var old = store.find("authority-policy", id, Policy.class);
      if (old.map(StateStore.State::version).orElse(0L) != request.expectedVersion())
        throw ApiException.conflict("Authority policy version changed");
      var saved = old.isEmpty() ? store.create("authority-policy", id, policy)
          : store.update("authority-policy", id, request.expectedVersion(), policy);
      store.event("AuthorityPolicyPublished", id, saved.version(), UUID.randomUUID(), null, saved);
      return saved;
    });
  }

  @GetMapping({"/api/authority-policies/{id}", "/internal/authority-policies/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Policy> current(@PathVariable String id) {
    return store.require("authority-policy", id, Policy.class);
  }

  @GetMapping({"/api/authority-policies/{id}/versions/{version}",
      "/internal/authority-policies/{id}/versions/{version}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Policy> version(@PathVariable String id, @PathVariable long version) {
    return store.version("authority-policy", id, version, Policy.class)
        .orElseThrow(() -> ApiException.missing("Authority policy version not found"));
  }
}
