package msc.services.acquisition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Expected whole-source accounting, pinned to owner plans; no packet-level quality claim. */
@RestController
public class SimulationManifestApi {
  public record Create(UUID scenarioId, List<String> planIds) {
    public Create {
      Objects.requireNonNull(scenarioId);
      planIds = List.copyOf(planIds);
      if (planIds.isEmpty()
          || planIds.size() > 64
          || new HashSet<>(planIds).size() != planIds.size())
        throw new IllegalArgumentException("1..64 distinct downlink plans required");
      for (String id : planIds) new SimulationSourceApi.Import(id);
    }
  }

  public record Expected(
      String planId,
      JsonNode plan,
      String planSha256,
      String payloadId,
      long byteCount,
      String sha256) {}

  public record Manifest(
      UUID scenarioId,
      List<Expected> expected,
      List<String> missingPlanIds,
      Map<String, StateStore.State<SimulationSourceApi.Source>> received,
      long expectedBytes,
      long receivedBytes,
      String completeness,
      String environment) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final org.springframework.jdbc.core.JdbcTemplate db;

  public SimulationManifestApi(
      StateStore store,
      ServiceHttp http,
      Json json,
      org.springframework.jdbc.core.JdbcTemplate db) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.db = db;
  }

  @PostMapping("/api/acquisition/simulation-manifests")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode create(
      @RequestBody Create request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-manifest-create:" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var expected = new ArrayList<Expected>();
    var payloads = new HashSet<String>();
    for (String id : request.planIds()) {
      var plan = http.get("simulator", "/internal/simulation/downlinks/" + id, JsonNode.class);
      var body = plan.path("body");
      var payload = body.path("payload");
      var size = payload.path("byteCount");
      String payloadId = body.path("request").path("payloadId").asText();
      if (!id.equals(plan.path("id").asText())
          || !positive(plan.path("version"))
          || !request
              .scenarioId()
              .toString()
              .equals(body.path("request").path("scenarioId").asText())
          || !request
              .scenarioId()
              .toString()
              .equals(payload.path("source").path("scenarioId").asText())
          || !payloadId.matches("[a-f0-9]{64}")
          || !payloadId.equals(payload.path("intentId").asText())
          || !payloads.add(payloadId)
          || !"SIMULATION".equals(payload.path("environment").asText())
          || !"ONBOARD".equals(payload.path("location").asText())
          || !positive(size)
          || size.asLong() > 67108864
          || !payload.path("sha256").asText().matches("[a-f0-9]{64}"))
        throw ApiException.invalid("Expected plan/payload identity, scope or size mismatch");
      expected.add(
          new Expected(
              id,
              plan,
              json.fingerprint(body),
              payloadId,
              size.asLong(),
              payload.path("sha256").asText()));
    }
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          request.planIds().stream()
              .sorted()
              .forEach(plan -> store.lock("simulation-acquisition-source:" + plan));
          var manifest = account(request.scenarioId(), expected);
          var saved = store.create("simulation-acquisition-manifest", id, manifest);
          for (String plan : request.planIds())
            db.update(
                "INSERT INTO simulation_manifest_source(manifest_id,plan_id) VALUES (?,?)",
                id,
                plan);
          publishComplete(saved, null);
          return saved;
        });
  }

  Manifest account(UUID scenario, List<Expected> expected) {
    var missing = new ArrayList<String>();
    var received = new TreeMap<String, StateStore.State<SimulationSourceApi.Source>>();
    long expectedBytes = 0, receivedBytes = 0;
    for (var item : expected) {
      expectedBytes = Math.addExact(expectedBytes, item.byteCount());
      var source =
          store.find(
              "simulation-acquisition-source", item.planId(), SimulationSourceApi.Source.class);
      if (source.isEmpty()) {
        missing.add(item.planId());
        continue;
      }
      var body = source.get().body();
      if (!body.receipt().path("body").path("planSha256").asText().equals(item.planSha256())
          || !body.sha256().equals(item.sha256())
          || body.byteCount() != item.byteCount()
          || !"SIMULATION".equals(body.environment())
          || !"RAW_SOURCE_STORED".equals(body.status()))
        throw ApiException.conflict("Stored source does not match pinned expected plan");
      received.put(item.planId(), source.get());
      receivedBytes = Math.addExact(receivedBytes, body.byteCount());
    }
    return new Manifest(
        scenario,
        List.copyOf(expected),
        List.copyOf(missing),
        Map.copyOf(received),
        expectedBytes,
        receivedBytes,
        missing.isEmpty() ? "COMPLETE" : "INCOMPLETE",
        "SIMULATION");
  }

  @PostMapping("/api/acquisition/simulation-manifests/{id}/refresh")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode refresh(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent(
        "simulation-manifest-refresh:" + actor.getName(), key, id, () -> refreshOwned(id));
  }

  // Invoked in the source import transaction, after bytes and source metadata are verified.
  void sourceStored(String planId) {
    for (String id :
        db.queryForList(
            "SELECT manifest_id FROM simulation_manifest_source WHERE plan_id=? ORDER BY"
                + " manifest_id",
            String.class,
            planId)) refreshOwned(id);
  }

  private StateStore.State<Manifest> refreshOwned(String id) {
    store.lock("simulation-acquisition-manifest:" + id);
    var old = read(id);
    var updated = account(old.body().scenarioId(), old.body().expected());
    if (json.fingerprint(updated).equals(json.fingerprint(old.body()))) return old;
    var saved = store.update("simulation-acquisition-manifest", id, old.version(), updated);
    publishComplete(saved, old.body());
    return saved;
  }

  private void publishComplete(StateStore.State<Manifest> saved, Manifest previous) {
    if (saved.body().completeness().equals("COMPLETE")
        && (previous == null || !previous.completeness().equals("COMPLETE")))
      store.event(
          "SimulationAcquisitionDataComplete",
          saved.id(),
          saved.version(),
          UUID.randomUUID(),
          null,
          saved);
  }

  private static boolean positive(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
  }

  @GetMapping({
    "/api/acquisition/simulation-manifests/{id}",
    "/internal/acquisition/simulation-manifests/{id}"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Manifest> read(@PathVariable String id) {
    return store.require("simulation-acquisition-manifest", id, Manifest.class);
  }
}
