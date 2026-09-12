package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.contracts.OperationResourceContracts.*;
import msc.domain.time.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SimulationDownlinkPlanTest extends SimulationPayloadTest {
  @Test
  void durableAllocationReplaysAndBookingVolumeCannotBeSpentTwice() {
    var f = new SimulationScenarioApiIT();
    f.json = json;
    f.owner = mock(ServiceHttp.class);
    var create = f.fixture();
    var actor = new UsernamePasswordAuthenticationToken("service", "unused");
    new SimulationScenarioApi(store, f.owner, json).create(create, "scenario", actor);
    var original = f.command(create, 20, 10);
    var catalogJson =
        json.tree(
            f.owner.get(
                "mission-definition",
                "/internal/catalog/image/versions/1",
                msc.contracts.CatalogContracts.CatalogEntry.class));
    ((com.fasterxml.jackson.databind.node.ObjectNode) catalogJson.get("template"))
        .put("operation", "DOWNLINK");
    var catalog = json.convert(catalogJson, msc.contracts.CatalogContracts.CatalogEntry.class);
    var profile =
        new OperationResourceProfile(
            Operation.DOWNLINK,
            new msc.contracts.MissionCatalogBindingContracts.CatalogReference("image", 1),
            new ExpectedCatalogResources(10, 1, 0),
            1.0);
    var entry =
        new SimulationCommandApi.Entry(
            original.load().commands().getFirst(),
            catalog,
            json.fingerprint(catalog),
            profile,
            20,
            120,
            SimulationCommandApi.Status.PENDING,
            null);
    String loadId = original.load().id().value();
    store.transaction(
        () ->
            store.create(
                "simulation-load:" + create.id(),
                loadId,
                new SimulationCommandApi.Ledger(original.load(), "fixture", List.of(entry))));
    var payload =
        new SimulationPayload.Manifest(
            "payload",
            new SimulationPayload.Intent(
                create.id().toString(),
                "image-command",
                "a".repeat(64),
                5,
                1,
                "MSC_SIMULATED_RAW_U8_V1"),
            "b".repeat(64),
            1_000_000,
            "c".repeat(64),
            "s3://simulator/test",
            "SIMULATION",
            "ONBOARD");
    var booking =
        new Booking(
            "booking",
            new Reservation(
                "station",
                1,
                create.spacecraftId(),
                new TimeWindow(MissionInstant.tai(1001), MissionInstant.tai(1020)),
                "prediction",
                1),
            BookingStatus.CONFIRMED,
            "receipt",
            "fixture");
    store.transaction(
        () -> {
          store.create("simulation-payload", "payload", payload);
          store.create("booking", "booking", booking);
          return store.create(
              "station", "station:1", new Station("station", 1, 0, 0, 0, 5, 1, "fixture"));
        });
    var api = new SimulationDownlinkPlanApi(store, json);
    var request =
        new SimulationDownlinkPlanApi.Request(
            create.id(), loadId, entry.command().id().value(), "payload", "booking");
    var first = api.plan(request, "first", actor);
    assertEquals(json.fingerprint(first), json.fingerprint(api.plan(request, "second", actor)));
    assertEquals(1, store.list("simulation-downlink-booking:booking", 10).size());
    assertEquals(1, store.history("simulation-downlink-plan", first.path("id").asText()).size());
    // The first real allocation already consumes the entire booking.
    var altered = json.tree(original.load());
    ((com.fasterxml.jackson.databind.node.ObjectNode) altered.at("/commands/0/id"))
        .put("value", UUID.randomUUID().toString());
    var nextLoad = json.convert(altered, msc.domain.spacecraftcontrol.CommandLoad.class);
    var nextEntry =
        new SimulationCommandApi.Entry(
            nextLoad.commands().getFirst(),
            catalog,
            json.fingerprint(catalog),
            profile,
            20,
            120,
            SimulationCommandApi.Status.PENDING,
            null);
    store.transaction(
        () ->
            store.create(
                "simulation-load:" + create.id(),
                "second-load",
                new SimulationCommandApi.Ledger(nextLoad, "fixture", List.of(nextEntry))));
    assertThrows(
        ApiException.class,
        () ->
            api.plan(
                new SimulationDownlinkPlanApi.Request(
                    create.id(),
                    "second-load",
                    nextEntry.command().id().value(),
                    "payload",
                    "booking"),
                "overbook",
                actor));
    var scenario =
        store
            .require(
                "simulation-scenario", create.id().toString(), SimulationScenarioApi.Scenario.class)
            .body();
    assertThrows(
        ApiException.class,
        () ->
            SimulationDownlinkPlanApi.validate(
                scenario,
                new SimulationCommandApi.Entry(
                    entry.command(),
                    catalog,
                    entry.catalogSha256(),
                    profile,
                    10,
                    110,
                    SimulationCommandApi.Status.PENDING,
                    null),
                payload,
                booking));
    var cancelled =
        new Booking(booking.id(), booking.request(), BookingStatus.CANCELLED, "cancel", "fixture");
    assertThrows(
        ApiException.class,
        () -> SimulationDownlinkPlanApi.validate(scenario, entry, payload, cancelled));
  }
}
