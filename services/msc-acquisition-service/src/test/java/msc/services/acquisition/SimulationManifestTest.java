package msc.services.acquisition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class SimulationManifestTest extends SimulationSourceTest {
  final UUID scenario = UUID.randomUUID();

  JsonNode plan() {
    return json.tree(
        new StateStore.State<>(
            id,
            1,
            Map.of(
                "request",
                Map.of("scenarioId", scenario, "payloadId", "c".repeat(64)),
                "payload",
                Map.of(
                    "intentId",
                    "c".repeat(64),
                    "source",
                    Map.of("scenarioId", scenario),
                    "byteCount",
                    bytes.length,
                    "sha256",
                    receipt.path("body").path("sha256").asText(),
                    "environment",
                    "SIMULATION",
                    "location",
                    "ONBOARD"))));
  }

  SimulationManifestApi manifests(JsonNode plan) {
    when(http.get("simulator", "/internal/simulation/downlinks/" + id, JsonNode.class))
        .thenReturn(plan);
    return new SimulationManifestApi(store, http, json);
  }

  void received(JsonNode plan) {
    ((ObjectNode) receipt.path("body")).put("planSha256", json.fingerprint(plan.path("body")));
    store.transaction(
        () ->
            store.create(
                "simulation-acquisition-source",
                id,
                new SimulationSourceApi.Source(
                    receipt,
                    json.fingerprint(receipt),
                    bytes.length,
                    receipt.path("body").path("sha256").asText(),
                    "s3://msc-acquisition/source",
                    "SIMULATION",
                    "RAW_SOURCE_STORED")));
  }

  @Test
  void missingSourceRemainsIncompleteThenCompletesExactlyOnce() {
    var plan = plan();
    var api = manifests(plan);
    var request = new SimulationManifestApi.Create(scenario, List.of(id));
    var created = api.create(request, "create", actor);
    String manifestId = created.path("id").asText();
    var initial = api.read(manifestId).body();
    assertEquals("INCOMPLETE", initial.completeness());
    assertEquals(List.of(id), initial.missingPlanIds());
    assertEquals(0, initial.receivedBytes());
    assertEquals(bytes.length, initial.expectedBytes());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    received(plan);
    var refreshed = api.refresh(manifestId, "refresh", actor);
    assertEquals("COMPLETE", api.read(manifestId).body().completeness());
    assertEquals(bytes.length, api.read(manifestId).body().receivedBytes());
    assertEquals(2, store.history("simulation-acquisition-manifest", manifestId).size());
    clearInvocations(http);
    assertEquals(
        json.fingerprint(refreshed), json.fingerprint(api.refresh(manifestId, "refresh", actor)));
    api.refresh(manifestId, "again", actor);
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    assertEquals(json.fingerprint(created), json.fingerprint(api.create(request, "create", actor)));
    verifyNoInteractions(http);
  }

  @Test
  void wrongSourcePlanCannotCloseGap() {
    var plan = plan();
    var api = manifests(plan);
    String manifestId =
        api.create(new SimulationManifestApi.Create(scenario, List.of(id)), "create", actor)
            .path("id")
            .asText();
    var changed = plan.deepCopy();
    ((ObjectNode) changed.path("body")).put("changed", true);
    received(changed);
    assertThrows(ApiException.class, () -> api.refresh(manifestId, "refresh", actor));
    assertEquals("INCOMPLETE", api.read(manifestId).body().completeness());
    assertEquals(1, store.history("simulation-acquisition-manifest", manifestId).size());
  }

  @Test
  void completeAtCreationAndEventFailureRollsBack() {
    var plan = plan();
    var api = manifests(plan);
    received(plan);
    db.execute("ALTER TABLE outbox ADD CONSTRAINT reject_manifest CHECK (false)");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              api.create(new SimulationManifestApi.Create(scenario, List.of(id)), "create", actor));
      assertTrue(store.list("simulation-acquisition-manifest", 10).isEmpty());
    } finally {
      db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_manifest");
    }
    var saved =
        api.create(new SimulationManifestApi.Create(scenario, List.of(id)), "create", actor);
    assertEquals("COMPLETE", saved.path("body").path("completeness").asText());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void rejectsForeignScenarioAndEmptyExpectation() {
    var api = manifests(plan());
    assertThrows(
        ApiException.class,
        () ->
            api.create(
                new SimulationManifestApi.Create(UUID.randomUUID(), List.of(id)), "wrong", actor));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SimulationManifestApi.Create(scenario, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SimulationManifestApi.Create(scenario, List.of(id, id)));
    assertTrue(store.list("simulation-acquisition-manifest", 10).isEmpty());
  }
}
