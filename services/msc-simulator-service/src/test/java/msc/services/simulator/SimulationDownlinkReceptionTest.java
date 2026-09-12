package msc.services.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.contracts.OperationResourceContracts.*;
import msc.domain.time.*;
import msc.platform.*;
import msc.services.simulator.SimulationReceptionApi.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SimulationDownlinkReceptionTest extends SimulationPayloadTest {
  record Fixture(String id, SimulationDownlinkPlanApi.Plan plan) {}

  final UsernamePasswordAuthenticationToken actor =
      new UsernamePasswordAuthenticationToken("service", "unused");

  Fixture seed() throws Exception {
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
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(new byte[1_000_000])),
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
    var saved = api.plan(request, "first", actor);
    return new Fixture(
        saved.path("id").asText(),
        json.convert(saved.path("body"), SimulationDownlinkPlanApi.Plan.class));
  }

  void complete(Fixture fixture) {
    var p = fixture.plan();
    var r = p.request();
    var e = p.command();
    store.transaction(
        () -> {
          var saved =
              store.require(
                  "simulation-load:" + r.scenarioId(),
                  r.loadId(),
                  SimulationCommandApi.Ledger.class);
          var effect =
              new SimulatorOperationEffects.Result(
                  SimulatorOperationEffects.Outcome.APPLIED,
                  new SimulatorOperationEffects.Reservoirs(1, 5),
                  new SimulatorOperationEffects.Reservoirs(0, 5),
                  0,
                  1,
                  1,
                  0,
                  10);
          var entry =
              new SimulationCommandApi.Entry(
                  e.command(),
                  e.catalog(),
                  e.catalogSha256(),
                  e.profile(),
                  e.startTick(),
                  e.completionTick(),
                  SimulationCommandApi.Status.EFFECT_APPLIED,
                  effect);
          store.update(
              "simulation-load:" + r.scenarioId(),
              r.loadId(),
              saved.version(),
              new SimulationCommandApi.Ledger(
                  saved.body().load(), saved.body().submissionSha256(), List.of(entry)));
          var state =
              store.require(
                  "simulation-scenario",
                  r.scenarioId().toString(),
                  SimulationScenarioApi.Scenario.class);
          var s = state.body();
          return store.update(
              "simulation-scenario",
              state.id(),
              state.version(),
              new SimulationScenarioApi.Scenario(
                  s.id(),
                  s.environment(),
                  s.mission(),
                  s.missionSha256(),
                  s.correlation(),
                  s.correlationSha256(),
                  s.resourceProfiles(),
                  s.resourceProfilesSha256(),
                  120,
                  effect.after(),
                  s.provenance()));
        });
  }

  void link(Fixture f) {
    new SimulationReceptionApi(store, json)
        .configure(
            f.plan().request().scenarioId(),
            f.plan().request().loadId(),
            new Configure(0, true, false, 0, "fixture"),
            "link",
            actor);
  }

  void cancel(Fixture f) {
    store.transaction(
        () -> {
          var b = store.require("booking", f.plan().booking().id(), Booking.class);
          return store.update(
              "booking",
              b.id(),
              b.version(),
              new Booking(
                  b.id(), b.body().request(), BookingStatus.CANCELLED, "cancel", "fixture"));
        });
  }

  @Test
  void onlyVerifiedCompletedTransferPublishesOneReceiptAndUnknownReplayStaysUnknown()
      throws Exception {
    var f = seed();
    var receiver = new SimulationDownlinkReceptionApi(store, json, objects);
    var request = new Attempt(Channel.RECONCILIATION);
    assertEquals(
        "LINK_NOT_CONFIGURED",
        receiver.receive(f.id(), request, "early", actor).path("reason").asText());
    link(f);
    assertEquals(
        "DOWNLINK_EFFECT_NOT_OBSERVED",
        receiver.receive(f.id(), request, "pending", actor).path("reason").asText());
    verifyNoInteractions(objects);
    complete(f);
    when(objects.read(anyString()))
        .thenAnswer(
            call -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return new ByteArrayInputStream(new byte[1_000_000]);
            });
    var receipt = receiver.receive(f.id(), request, "received", actor);
    assertEquals("OBSERVED", receipt.path("belief").asText());
    assertEquals(1_000_000, receipt.at("/receipt/byteCount").asLong());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type='SimulatedPayloadReceived'",
            Integer.class));
    clearInvocations(objects);
    assertEquals(
        "UNKNOWN", receiver.receive(f.id(), request, "early", actor).path("belief").asText());
    cancel(f);
    assertEquals(
        json.fingerprint(receipt),
        json.fingerprint(receiver.receive(f.id(), request, "later", actor)));
    verifyNoInteractions(objects);
    assertEquals(1, store.history("simulation-downlink-receipt", f.id()).size());
  }

  @Test
  void corruptBytesAndCancellationDuringReadCannotProduceReceipt() throws Exception {
    var f = seed();
    link(f);
    complete(f);
    var receiver = new SimulationDownlinkReceptionApi(store, json, objects);
    var request = new Attempt(Channel.RECONCILIATION);
    when(objects.read(anyString())).thenReturn(new ByteArrayInputStream(new byte[2]));
    assertThrows(ApiException.class, () -> receiver.receive(f.id(), request, "short", actor));
    assertTrue(store.list("simulation-downlink-receipt", 10).isEmpty());
    when(objects.read(anyString()))
        .thenAnswer(
            call -> {
              cancel(f);
              return new ByteArrayInputStream(new byte[1_000_000]);
            });
    assertEquals(
        "BOOKING_NOT_CONFIRMED",
        receiver.receive(f.id(), request, "cancelled", actor).path("reason").asText());
    assertTrue(store.list("simulation-downlink-receipt", 10).isEmpty());
  }

  @Test
  void outboxFailureRollsBackReceiptAndAllowsSameKeyRetry() throws Exception {
    var f = seed();
    link(f);
    complete(f);
    var receiver = new SimulationDownlinkReceptionApi(store, json, objects);
    when(objects.read(anyString()))
        .thenAnswer(call -> new ByteArrayInputStream(new byte[1_000_000]));
    db.execute(
        "ALTER TABLE outbox ADD CONSTRAINT reject_received CHECK (event_type <>"
            + " 'SimulatedPayloadReceived')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> receiver.receive(f.id(), new Attempt(Channel.RECONCILIATION), "retry", actor));
      assertTrue(store.list("simulation-downlink-receipt", 10).isEmpty());
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_received");
    }
    assertEquals(
        "OBSERVED",
        receiver
            .receive(f.id(), new Attempt(Channel.RECONCILIATION), "retry", actor)
            .path("belief")
            .asText());
  }
}
