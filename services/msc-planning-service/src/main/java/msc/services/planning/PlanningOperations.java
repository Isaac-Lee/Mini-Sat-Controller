package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.*;
import msc.contracts.OperationResourceContracts.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.Evidence;

/** Collects exact owner versions for operation selection and resource effects. */
final class PlanningOperations {
  record Captured(Evidence bindings, Evidence resourceProfiles, Map<Role, Evidence> catalogs) {
    Captured {
      Objects.requireNonNull(bindings);
      Objects.requireNonNull(resourceProfiles);
      catalogs = Map.copyOf(catalogs);
    }
  }

  private final ServiceHttp http;
  private final Json json;

  PlanningOperations(ServiceHttp http, Json json) {
    this.http = http;
    this.json = json;
  }

  private Evidence get(String path) {
    var value = http.get("mission-definition", path, JsonNode.class);
    if (value == null || !value.isObject())
      throw ApiException.invalid("Operation owner returned no object");
    return new Evidence("mission-definition", path, json.fingerprint(value), value);
  }

  Captured collect(MissionProfile mission, Evidence imagingCatalog) {
    String craft = PlanningInputs.segment(mission.spacecraftId());
    var bindings = get("/internal/mission-catalog-bindings/" + craft);
    var resources = get("/internal/operation-resource-profiles/" + craft);
    var roles = json.convert(bindings.value().path("body"), Bindings.class);
    var catalogs = new EnumMap<Role, Evidence>(Role.class);
    for (var role : roles.roles()) {
      var reference = role.reference();
      catalogs.put(
          role.role(),
          role.role() == Role.IMAGING
              ? imagingCatalog
              : get(
                  "/internal/catalog/"
                      + PlanningInputs.segment(reference.catalogId())
                      + "/versions/"
                      + reference.catalogVersion()));
    }
    var result = new Captured(bindings, resources, catalogs);
    validate(mission, result);
    return result;
  }

  void validate(MissionProfile mission, Captured captured) {
    var bindings = json.convert(verified(captured.bindings()).path("body"), Bindings.class);
    var profiles = profiles(mission, captured.resourceProfiles());
    if (bindings == null
        || !bindings.spacecraftId().equals(mission.spacecraftId())
        || !bindings.missionDefinitionVersion().equals(mission.missionDefinitionVersion())
        || !captured.bindings().value().path("id").asText().equals(mission.spacecraftId())
        || captured.bindings().value().path("version").asLong() < 1
        || !bindings
            .reference(Role.IMAGING)
            .orElseThrow()
            .equals(new CatalogReference(mission.catalogId(), mission.catalogVersion())))
      throw ApiException.invalid("Operation catalog mission binding mismatch");
    var expectedRoles = EnumSet.noneOf(Role.class);
    for (var binding : bindings.roles()) {
      expectedRoles.add(binding.role());
      var evidence = captured.catalogs().get(binding.role());
      if (evidence == null) throw ApiException.invalid("Bound operation catalog is absent");
      var catalog = json.convert(verified(evidence), CatalogEntry.class);
      if (!binding.reference().equals(new CatalogReference(catalog.id(), catalog.version()))
          || !binding.role().operation().equals(catalog.template().operation()))
        throw ApiException.invalid("Operation role and exact catalog mismatch");
      profile(profiles, catalog);
    }
    if (!captured.catalogs().keySet().equals(expectedRoles))
      throw ApiException.invalid("Captured operation catalog set differs from role bindings");
  }

  Profiles profiles(MissionProfile mission, Evidence evidence) {
    var value = verified(evidence);
    var profiles = json.convert(value.path("body"), Profiles.class);
    if (profiles == null
        || !profiles.spacecraftId().equals(mission.spacecraftId())
        || !profiles.missionDefinitionVersion().equals(mission.missionDefinitionVersion())
        || !value.path("id").asText().equals(mission.spacecraftId())
        || value.path("version").asLong() < 1)
      throw ApiException.invalid("Operation resource mission binding mismatch");
    return profiles;
  }

  OperationResourceProfile profile(Profiles profiles, CatalogEntry catalog) {
    var reference = new CatalogReference(catalog.id(), catalog.version());
    var profile =
        profiles.profiles().stream()
            .filter(p -> p.catalog().equals(reference))
            .findFirst()
            .orElseThrow(() -> ApiException.invalid("Exact operation resource profile is absent"));
    var expected = profile.expected();
    var actual = catalog.resources();
    if (!profile.operation().name().equals(catalog.template().operation())
        || expected.powerWatts() != actual.powerWatts()
        || expected.generatedMegabytes() != actual.generatedMegabytes()
        || expected.propellantKilograms() != actual.propellantKilograms())
      throw ApiException.invalid("Operation resource profile does not match the pinned catalog");
    return profile;
  }

  private JsonNode verified(Evidence evidence) {
    if (evidence == null || !json.fingerprint(evidence.value()).equals(evidence.sha256()))
      throw ApiException.invalid("Operation evidence hash mismatch");
    return evidence.value();
  }
}
