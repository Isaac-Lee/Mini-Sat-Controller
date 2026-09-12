package msc.services.missiondefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.MissionCatalogBindingContracts.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * ADMIN-published, versioned multi-activity catalog bindings for a spacecraft, keyed by explicit
 * operation role ({@code IMAGING}, {@code DOWNLINK}, optional {@code MANEUVER}).
 *
 * <p>This is a <b>new, separate</b> owner API from {@link CatalogApi}: {@code
 * CatalogContracts.MissionProfile} keeps pinning exactly one {@code (catalogId, catalogVersion)}
 * pair and every existing single-catalog reader of it (including {@link CatalogApi} itself and
 * Planning's current read path) is untouched by this class. Publish-time validation resolves
 * every referenced {@code CatalogEntry} at its exact version from the same approved-activity
 * store {@link CatalogApi} uses. A defensive unapproved-activity check is kept even though {@code
 * CatalogEntry}'s own constructor already guarantees {@code activity().approved()} on every
 * stored entry, making that branch currently unreachable (see the comment at the check itself).
 * It does not re-validate what the {@code CatalogEntry} record constructor already enforces
 * (template reference consistency, bounded duration), and it invents no duration, resource,
 * instrument or operational parameter of its own — every such value lives only on the resolved
 * catalog entry.
 *
 * <p>Each role also binds to an exact {@code CommandTemplate.operation}: a role is not a free-form
 * label an entry can be filed under regardless of what it actually does. {@link Role#operation()}
 * declares the required operation per role, and publish rejects a mismatch (see {@code
 * MissionCatalogBindingContracts.Role} for which operations are actually exercised elsewhere in
 * this codebase versus declared-but-unexercised).
 *
 * <p><b>Precedence against the legacy pin:</b> {@code MissionProfile} keeps pinning one {@code
 * (catalogId, catalogVersion)} that Planning currently reads for imaging. To avoid a second,
 * possibly divergent, source of truth for that same activity, the {@code IMAGING} role's catalog
 * reference must equal the spacecraft's current {@code MissionProfile} pin exactly; publish
 * rejects a mismatch.
 *
 * <p>This class does not wire anything into Planning. Planning currently reads only the single
 * legacy pin via {@code PlanningInputs} at {@code /internal/catalog/{id}/versions/{version}}; see
 * {@code docs/backend/mission-catalog-bindings.md} for the integration request describing how a
 * future Planning change could consume these bindings instead.
 */
@RestController
public class MissionCatalogBindingApi {
  private final StateStore store;

  public MissionCatalogBindingApi(StateStore store) {
    this.store = store;
  }

  private static String catalogKey(String id, long version) {
    return id + ":" + version;
  }

  @PostMapping("/api/mission-catalog-bindings")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode publish(
      @RequestBody Publish request,
      @RequestHeader("Idempotency-Key") String idempotency,
      Authentication actor) {
    return store.idempotent(
        "mission-catalog-bindings:" + actor.getName(),
        idempotency,
        request,
        () -> {
          var bindings = request.bindings();
          String id = bindings.spacecraftId();
          store.lock("mission-catalog-bindings:" + id);
          var mission = store.require("mission", id, MissionProfile.class).body();
          if (!mission.missionDefinitionVersion().equals(bindings.missionDefinitionVersion()))
            throw ApiException.invalid(
                "Mission catalog bindings must bind the configured mission definition");
          var legacyImagingPin =
              new CatalogReference(mission.catalogId(), mission.catalogVersion());
          // Bindings' own constructor already requires an IMAGING role, so this is never absent;
          // orElseThrow is defensive rather than a reachable branch.
          var imagingReference =
              bindings
                  .reference(Role.IMAGING)
                  .orElseThrow(() -> ApiException.invalid("IMAGING role binding is required"));
          if (!imagingReference.equals(legacyImagingPin))
            throw ApiException.invalid(
                "IMAGING role must match the mission's legacy catalog pin exactly ("
                    + legacyImagingPin.catalogId()
                    + ":"
                    + legacyImagingPin.catalogVersion()
                    + ")");
          for (var binding : bindings.roles()) {
            var reference = binding.reference();
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
                  "Role " + binding.role() + " references an unapproved catalog activity");
            if (!entry.template().operation().equals(binding.role().operation()))
              throw ApiException.invalid(
                  "Role "
                      + binding.role()
                      + " requires catalog operation "
                      + binding.role().operation()
                      + " but the bound entry declares "
                      + entry.template().operation());
          }
          var old = store.find("mission-catalog-bindings", id, Bindings.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Mission catalog bindings version changed");
          var saved =
              old.isEmpty()
                  ? store.create("mission-catalog-bindings", id, bindings)
                  : store.update(
                      "mission-catalog-bindings", id, request.expectedVersion(), bindings);
          store.event(
              "MissionCatalogBindingsPublished",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @GetMapping({"/api/mission-catalog-bindings/{id}", "/internal/mission-catalog-bindings/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Bindings> current(@PathVariable String id) {
    return store.require("mission-catalog-bindings", id, Bindings.class);
  }

  @GetMapping({
    "/api/mission-catalog-bindings/{id}/versions/{version}",
    "/internal/mission-catalog-bindings/{id}/versions/{version}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Bindings> version(@PathVariable String id, @PathVariable long version) {
    return store
        .version("mission-catalog-bindings", id, version, Bindings.class)
        .orElseThrow(() -> ApiException.missing("Mission catalog bindings version not found"));
  }
}
