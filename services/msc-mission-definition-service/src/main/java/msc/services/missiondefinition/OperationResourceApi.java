package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.UUID;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * ADMIN-published, versioned per-operation resource evidence for a spacecraft: for each bound
 * catalog entry, an explicit {@code expected} snapshot of the catalog's own resource numbers plus
 * (for {@code DOWNLINK}) an explicit downlink rate.
 *
 * <p>This is a <b>new, separate</b> owner API from {@link CatalogApi} and {@link
 * MissionCatalogBindingApi}: {@code CatalogContracts.MissionProfile}'s legacy single-catalog pin
 * and {@code MissionCatalogBindingContracts.Bindings}' per-role catalog bindings are both
 * untouched by this class, and this class establishes no precedence over either — see {@code
 * OperationResourceContracts}'s class javadoc for what this slice is and is not.
 *
 * <p>Publish-time validation resolves every referenced {@link CatalogEntry} at its exact version
 * from the same approved-activity store {@link CatalogApi} uses, via a key string byte-identical
 * to {@code CatalogApi.key} ({@code id + ":" + version}). It rejects a profile whose resolved
 * catalog entry's {@code template().operation()} does not equal {@code operation.name()} — this
 * is the central invariant of this slice: a profile is never accepted as evidence for an
 * operation the bound catalog entry does not actually declare. It also rejects a profile whose
 * {@code expected} disagrees, field by field, with the resolved entry's {@code resources()} (see
 * {@code OperationResourceContracts}'s tripwire javadoc for why this check exists and what it does
 * not mean).
 *
 * <p>A defensive unapproved-activity check is kept even though {@link CatalogEntry}'s own
 * constructor already guarantees {@code activity().approved()} on every stored entry, making that
 * branch currently unreachable — see the comment at the check itself, and the same defensive
 * check kept for the same reason in {@code MissionCatalogBindingApi}.
 */
@RestController
public class OperationResourceApi {
  private final StateStore store;

  public OperationResourceApi(StateStore store) {
    this.store = store;
  }

  private static String catalogKey(String id, long version) {
    return id + ":" + version;
  }

  @PostMapping("/api/operation-resource-profiles")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String idempotency,
      Authentication actor) {
    return store.idempotent(
        "operation-resource-profiles:" + actor.getName(),
        idempotency,
        request,
        () -> {
          var profiles = request.profiles();
          String id = profiles.spacecraftId();
          store.lock("operation-resource-profiles:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(profiles.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Operation resource profiles must bind the configured mission definition");
          // Profiles' own constructor already rejects a duplicate CatalogReference across the
          // list, so this set only re-derives the same fact for a clearer publish-time message;
          // it is not a second source of truth for uniqueness.
          var seen = new HashSet<CatalogReference>();
          for (var profile : profiles.profiles()) {
            var reference = profile.catalog();
            if (!seen.add(reference))
              throw ApiException.invalid(
                  "Duplicate catalog reference: "
                      + reference.catalogId()
                      + ":"
                      + reference.catalogVersion());
            var entry =
                store
                    .require(
                        "catalog",
                        catalogKey(reference.catalogId(), reference.catalogVersion()),
                        CatalogEntry.class)
                    .body();
            // CatalogEntry's own constructor already forbids storing an unapproved activity, so
            // this is unreachable under the current domain invariant; kept as defense in depth
            // in case that invariant ever loosens or a record is read by a path that bypasses it.
            if (!entry.activity().approved())
              throw ApiException.invalid(
                  "Operation "
                      + profile.operation()
                      + " references an unapproved catalog activity");
            if (!entry.template().operation().equals(profile.operation().name()))
              throw ApiException.invalid(
                  "Operation "
                      + profile.operation()
                      + " requires catalog operation "
                      + profile.operation().name()
                      + " but the bound entry declares "
                      + entry.template().operation());
            var resources = entry.resources();
            var expected = profile.expected();
            if (resources.powerWatts() != expected.powerWatts()
                || resources.generatedMegabytes() != expected.generatedMegabytes()
                || resources.propellantKilograms() != expected.propellantKilograms())
              throw ApiException.invalid(
                  "Expected resources for "
                      + reference.catalogId()
                      + ":"
                      + reference.catalogVersion()
                      + " do not match the resolved catalog entry");
          }
          var old = store.find("operation-resource-profiles", id, Profiles.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Operation resource profiles version changed");
          var saved =
              old.isEmpty()
                  ? store.create("operation-resource-profiles", id, profiles)
                  : store.update(
                      "operation-resource-profiles", id, request.expectedVersion(), profiles);
          store.event(
              "OperationResourceProfilesPublished",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({
    "/api/operation-resource-profiles/{id}",
    "/internal/operation-resource-profiles/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Profiles> current(@PathVariable String id) {
    return store.require("operation-resource-profiles", id, Profiles.class);
  }

  @GetMapping({
    "/api/operation-resource-profiles/{id}/versions/{version}",
    "/internal/operation-resource-profiles/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Profiles> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("operation-resource-profiles", id, version, Profiles.class)
        .orElseThrow(() -> ApiException.missing("Operation resource profiles version not found"));
  }
}
