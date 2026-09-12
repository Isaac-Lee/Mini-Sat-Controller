package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogApi {
  private final StateStore store;
  private final Json json;

  public CatalogApi(StateStore store, Json json) {
    this.store = store;
    this.json = json;
  }

  private static String key(String id, long version) {
    return id + ":" + version;
  }

  @PostMapping("/api/catalog")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode create(
      @RequestBody CatalogEntry entry,
      @RequestHeader("Idempotency-Key") String idempotency,
      Authentication actor) {
    return store.idempotent(
        "catalog:" + actor.getName(),
        idempotency,
        entry,
        () -> {
          var saved = store.create("catalog", key(entry.id(), entry.version()), entry);
          store.event(
              "ActivityDefinitionPublished",
              saved.id(),
              entry.version(),
              UUID.randomUUID(),
              null,
              entry);
          return saved;
        },
        // Opt-in legacy-replay rescue, this call site only (CatalogApi.mission stays strict: its
        // request has no Set field). Existing `idempotency` rows under scope `catalog:<actor>`
        // were fingerprinted before CatalogEntry's Set fields (via ActivityDefinition and
        // ParameterRule) had a canonical, JVM-stable order, so a byte-identical retry landing on
        // a different replica now computes a different fingerprint and would otherwise get a hard
        // 409. StateStore.legacyReplay decides, fail-closed, whether the stored response actually
        // matches this request; any failed proof falls through to the strict conflict untouched.
        body -> json.convert(body, CatalogEntry.class));
  }

  @GetMapping({"/api/catalog/{id}/versions/{version}", "/internal/catalog/{id}/versions/{version}"})
  public CatalogEntry get(@PathVariable String id, @PathVariable long version) {
    return store.require("catalog", key(id, version), CatalogEntry.class).body();
  }

  @GetMapping("/api/catalog")
  public List<StateStore.State<JsonNode>> list(@RequestParam(defaultValue = "100") int limit) {
    return store.list("catalog", limit);
  }

  @PostMapping("/api/missions")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode mission(
      @RequestBody MissionProfile profile,
      @RequestHeader("Idempotency-Key") String idempotency,
      Authentication actor) {
    return store.idempotent(
        "mission:" + actor.getName(),
        idempotency,
        profile,
        () -> {
          store.require(
              "catalog", key(profile.catalogId(), profile.catalogVersion()), CatalogEntry.class);
          var saved = store.create("mission", profile.spacecraftId(), profile);
          store.event(
              "MissionDefinitionPublished",
              saved.id(),
              saved.version(),
              UUID.randomUUID(),
              null,
              profile);
          return saved;
        });
  }

  @GetMapping({"/api/missions/{id}", "/internal/missions/{id}"})
  public MissionProfile mission(@PathVariable String id) {
    return store.require("mission", id, MissionProfile.class).body();
  }

  @GetMapping("/internal/missions")
  public List<StateStore.State<JsonNode>> missions() {
    return store.list("mission", 500);
  }
}
