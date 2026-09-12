package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.*;
import msc.contracts.OperationResourceContracts.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.Evidence;
import org.junit.jupiter.api.Test;

class PlanningOperationsTest {
  final PlanningGeometryTest f = new PlanningGeometryTest();
  final ServiceHttp http = mock(ServiceHttp.class);
  final PlanningOperations operations = new PlanningOperations(http, f.json);
  final MissionProfile mission =
      new MissionProfile("norad-63229", "catalog", 1, "v1", 100, 10, 10, 10, 0, "sim", "test");

  Evidence catalog(String operation, double produced) {
    var node = (ObjectNode) f.catalog().value().deepCopy();
    node.put("id", operation.toLowerCase());
    ((ObjectNode) node.path("activity").path("id")).put("value", operation.toLowerCase());
    ((ObjectNode) node.path("template")).put("operation", operation);
    ((ObjectNode) node.path("resources")).put("generatedMegabytes", produced);
    return f.evidence(node);
  }

  Evidence profiles(Evidence catalog, double rate) {
    var entry = f.json.convert(catalog.value(), CatalogEntry.class);
    var profile =
        new OperationResourceProfile(
            Operation.valueOf(entry.template().operation()),
            new CatalogReference(entry.id(), entry.version()),
            new ExpectedCatalogResources(
                entry.resources().powerWatts(),
                entry.resources().generatedMegabytes(),
                entry.resources().propellantKilograms()),
            "DOWNLINK".equals(entry.template().operation()) ? rate : null);
    return state(
        new Profiles(mission.spacecraftId(), "v1", "SIMULATION", List.of(profile), "test", "test"));
  }

  Evidence state(Object body) {
    return f.evidence(Map.of("id", mission.spacecraftId(), "version", 2, "body", body));
  }

  PlanningOperations.Captured captured() {
    var image = f.json.convert(f.catalog().value(), CatalogEntry.class);
    var downlink = catalog("DOWNLINK", .5);
    var imageProfile =
        new OperationResourceProfile(
            Operation.IMAGE,
            new CatalogReference(image.id(), image.version()),
            new ExpectedCatalogResources(1, 1, 0),
            null);
    var downlinkProfiles =
        f.json.convert(profiles(downlink, 2).value().path("body"), Profiles.class);
    var resources =
        state(
            new Profiles(
                mission.spacecraftId(),
                "v1",
                "SIMULATION",
                List.of(imageProfile, downlinkProfiles.profiles().getFirst()),
                "test",
                "test"));
    var bindings =
        state(
            new Bindings(
                mission.spacecraftId(),
                "v1",
                List.of(
                    new RoleBinding(Role.IMAGING, imageProfile.catalog()),
                    new RoleBinding(
                        Role.DOWNLINK, downlinkProfiles.profiles().getFirst().catalog())),
                "test"));
    return new PlanningOperations.Captured(
        bindings, resources, Map.of(Role.IMAGING, f.catalog(), Role.DOWNLINK, downlink));
  }

  @Test
  void ownerCollectionPinsExactVersionsAndRetainsBothResourceEffects() {
    var source = captured();
    when(http.get(
            "mission-definition", "/internal/mission-catalog-bindings/norad-63229", JsonNode.class))
        .thenReturn(source.bindings().value());
    when(http.get(
            "mission-definition",
            "/internal/operation-resource-profiles/norad-63229",
            JsonNode.class))
        .thenReturn(source.resourceProfiles().value());
    when(http.get("mission-definition", "/internal/catalog/downlink/versions/1", JsonNode.class))
        .thenReturn(source.catalogs().get(Role.DOWNLINK).value());
    var captured = operations.collect(mission, f.catalog());
    var profiles = operations.profiles(mission, captured.resourceProfiles());
    var entry = f.json.convert(captured.catalogs().get(Role.DOWNLINK).value(), CatalogEntry.class);
    var effect = operations.profile(profiles, entry);
    assertEquals(.5, effect.expected().generatedMegabytes());
    assertEquals(2, effect.downlinkMegabytesPerSecond());
    assertEquals(2, captured.resourceProfiles().value().path("version").asLong());
    var restored = f.json.read(f.json.write(captured), PlanningOperations.Captured.class);
    // JSON preserves the integer value, not Jackson's in-memory IntNode/LongNode subtype.
    assertEquals(f.json.fingerprint(captured), f.json.fingerprint(restored));
    assertDoesNotThrow(() -> operations.validate(mission, restored));
    verify(http, never())
        .get("mission-definition", "/internal/catalog/catalog/versions/1", JsonNode.class);
  }

  @Test
  void staleMissionRoleAndMissingResourceProfilesAreRejected() {
    var source = captured();
    var stale =
        new MissionProfile(
            mission.spacecraftId(), "catalog", 1, "v2", 100, 10, 10, 10, 0, "sim", "test");
    assertThrows(ApiException.class, () -> operations.validate(stale, source));
    var wrong = new EnumMap<Role, Evidence>(source.catalogs());
    wrong.put(Role.DOWNLINK, f.catalog());
    assertThrows(
        ApiException.class,
        () ->
            operations.validate(
                mission,
                new PlanningOperations.Captured(
                    source.bindings(), source.resourceProfiles(), wrong)));
    assertThrows(
        ApiException.class,
        () ->
            operations.validate(
                mission,
                new PlanningOperations.Captured(
                    source.bindings(), profiles(f.catalog(), 0), source.catalogs())));
  }

  @Test
  void badHashesAndChangedCatalogNumbersCannotBeUsedAsApprovedEffects() {
    var source = captured();
    var bad = new Evidence("owner", "/test", "bad", source.resourceProfiles().value());
    assertThrows(
        ApiException.class,
        () ->
            operations.validate(
                mission,
                new PlanningOperations.Captured(source.bindings(), bad, source.catalogs())));
    var changed = catalog("DOWNLINK", 9);
    assertThrows(
        ApiException.class,
        () ->
            operations.profile(
                operations.profiles(mission, source.resourceProfiles()),
                f.json.convert(changed.value(), CatalogEntry.class)));
  }
}
