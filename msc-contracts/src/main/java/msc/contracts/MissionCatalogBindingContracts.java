package msc.contracts;

import static msc.domain.shared.Checks.*;

import java.util.*;

/**
 * Version-1 published multi-activity mission catalog bindings; no server or persistence
 * dependencies.
 *
 * <p>{@link CatalogContracts.MissionProfile} pins exactly one {@code (catalogId, catalogVersion)}
 * pair, which is genuinely insufficient once a mission needs more than one approved activity
 * definition (for example imaging and downlink). This contract is a <b>new, separate</b> binding
 * keyed by {@code spacecraftId} that lets a spacecraft bind several approved catalog references
 * by explicit, bounded operation role. It does not replace, extend or reinterpret {@code
 * MissionProfile}: existing single-catalog reads of {@code MissionProfile} are untouched and keep
 * working exactly as before.
 *
 * <p>Nothing here is invented: every {@code (catalogId, catalogVersion)} is an explicit
 * administrator input, resolved at publish time against the already-approved activity catalog
 * (see {@code CatalogContracts.CatalogEntry}). No duration, resource, instrument or operational
 * parameter is derived here — those live only on the resolved catalog entry. Nothing may be
 * derived from NORAD identity or public orbit elements: SPACEEYE-T1 / NORAD 63229 is public GP
 * orbit tracking only, never a hardware or instrument source.
 */
public final class MissionCatalogBindingContracts {
  private MissionCatalogBindingContracts() {}

  /**
   * Bounded, explicitly enumerated operation roles. No other role may be bound. Each role
   * declares the exact {@code CatalogContracts.CommandTemplate.operation} a catalog entry bound
   * to it must carry, checked by {@code MissionCatalogBindingApi} at publish time against the
   * resolved {@code CatalogEntry.template()} — a role is never merely a free-form map key.
   *
   * <p>Only {@code IMAGING -> "IMAGE"} is backed by an activity/template that exists anywhere in
   * this codebase today (see {@code CatalogApiTest.ENTRY} and {@code
   * scripts/verify-planning-inputs.py}, both of which use operation {@code "IMAGE"} for an
   * imaging activity). {@code DOWNLINK -> "DOWNLINK"} and {@code MANEUVER -> "MANEUVER"} are
   * declared-but-unexercised simulation contract values: no catalog entry or fixture with those
   * operations exists yet in this repository, and none is invented here — publishing a binding
   * for either role simply requires that some future approved catalog entry declare that exact
   * operation string.
   */
  public enum Role {
    IMAGING("IMAGE"),
    DOWNLINK("DOWNLINK"),
    MANEUVER("MANEUVER");

    private final String operation;

    Role(String operation) {
      this.operation = operation;
    }

    public String operation() {
      return operation;
    }
  }

  /** An exact, single catalog activity version, never a range or a "latest" pointer. */
  public record CatalogReference(String catalogId, long catalogVersion) {
    public CatalogReference {
      text(catalogId);
      positive(catalogVersion);
    }
  }

  public record RoleBinding(Role role, CatalogReference reference) {
    public RoleBinding {
      Objects.requireNonNull(role, "Role is required");
      Objects.requireNonNull(reference, "Catalog reference is required");
    }
  }

  /**
   * Multi-activity catalog bindings for one spacecraft. {@code IMAGING} and {@code DOWNLINK} are
   * required because that is what the accepted final flow needs; {@code MANEUVER} is optional
   * rather than inventing a manoeuvre the simulation does not perform. Duplicate roles are
   * rejected. {@code missionDefinitionVersion} must match the spacecraft's currently stored
   * {@link CatalogContracts.MissionProfile}, exactly as {@code AgilityContracts.Model} and {@code
   * SimulationPlanningContracts.Model} bind it.
   *
   * <p><b>Precedence against the legacy single-catalog pin:</b> {@code MissionProfile} keeps
   * pinning one {@code (catalogId, catalogVersion)} that Planning currently reads for imaging
   * ({@code PlanningInputs.java:280-282}). This contract does not create a second, possibly
   * divergent, source of truth for that activity: the {@code IMAGING} role's {@link
   * CatalogReference} MUST equal the spacecraft's current {@code MissionProfile} {@code
   * (catalogId, catalogVersion)} pin exactly, enforced by {@code MissionCatalogBindingApi} at
   * publish time (not here, since this contract has no persistence dependency to read the
   * legacy pin from). A consumer that observes them disagreeing has found either a bug or a
   * stale read; it must not average, prefer one, or silently pick either — it should treat the
   * bindings as unpublishable-until-corrected, exactly as the API refuses to publish such a state.
   *
   * <p><b>Known consequence, not yet resolved:</b> {@code CatalogApi.mission} only ever {@code
   * store.create}s a {@code MissionProfile} once — there is no CAS/update route for it anywhere
   * in the mission-definition service, so its {@code (catalogId, catalogVersion)} pin is
   * immutable for the life of a spacecraft record. Combined with the strict-equality rule above,
   * binding {@code IMAGING} to any other version of the same activity — including a newer,
   * independently approved catalog entry — is unreachable through this API today: catalog
   * rotation for imaging is blocked until a mission-profile update path exists. This is a
   * deliberate default (it is the right one to keep a single source of truth), not an oversight,
   * but it is an open decision for a future change: either (a) add a CAS/update route for {@code
   * MissionProfile} so the legacy pin can move, or (b) relax this equality to a weaker
   * consistency rule. Neither is implemented by this contract or its API.
   */
  public record Bindings(
      String spacecraftId,
      String missionDefinitionVersion,
      List<RoleBinding> roles,
      String provenance) {
    public Bindings {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(provenance);
      Objects.requireNonNull(roles, "Role bindings are required");
      roles = List.copyOf(roles);
      var seen = EnumSet.noneOf(Role.class);
      for (var binding : roles) {
        Objects.requireNonNull(binding, "Role binding entries must not be null");
        if (!seen.add(binding.role()))
          throw new IllegalArgumentException("Duplicate role binding: " + binding.role());
      }
      if (!seen.contains(Role.IMAGING) || !seen.contains(Role.DOWNLINK))
        throw new IllegalArgumentException(
            "Mission catalog bindings require both IMAGING and DOWNLINK roles");
    }

    /** The exact catalog reference bound to a role, if that role is present. */
    public Optional<CatalogReference> reference(Role role) {
      return roles.stream()
          .filter(binding -> binding.role() == role)
          .map(RoleBinding::reference)
          .findFirst();
    }
  }

  public record Publish(long expectedVersion, Bindings bindings) {
    public Publish {
      if (expectedVersion < 0 || bindings == null)
        throw new IllegalArgumentException("Expected version and bindings are required");
    }
  }
}
