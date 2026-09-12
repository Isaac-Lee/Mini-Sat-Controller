package msc.services.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class TelemetryApi {
  public record View(
      StateStore.State<Estimate> estimate, MissionInstant evaluatedAt, Confidence confidence) {}

  private final StateStore store;
  private final TelemetryStore telemetry;
  private final msc.ports.Clock clock;

  public TelemetryApi(StateStore store, TelemetryStore telemetry, msc.ports.Clock clock) {
    this.store = store;
    this.telemetry = telemetry;
    this.clock = clock;
  }

  @PostMapping("/api/telemetry-bindings")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode bind(
      @RequestBody Binding binding,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "telemetry-bind:" + actor.getName(), key, binding, () -> telemetry.bind(binding));
  }

  @PostMapping("/internal/telemetry")
  @PreAuthorize("hasRole('SERVICE')")
  public JsonNode ingest(
      @RequestBody Frame frame,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "telemetry-ingest:" + actor.getName(),
        key,
        frame,
        () -> telemetry.ingest(frame, UUID.randomUUID(), null));
  }

  @GetMapping({
    "/api/spacecraft-estimates/{spacecraftId}",
    "/internal/spacecraft-estimates/{spacecraftId}"
  })
  public View current(@PathVariable String spacecraftId) {
    var state = store.require("telemetry-estimate", spacecraftId, Estimate.class);
    var now = clock.now();
    return new View(state, now, state.body().confidence(now));
  }

  @GetMapping({
    "/api/spacecraft-estimates/{spacecraftId}/versions/{version}",
    "/internal/spacecraft-estimates/{spacecraftId}/versions/{version}"
  })
  public StateStore.State<Estimate> historical(
      @PathVariable String spacecraftId, @PathVariable long version) {
    return store
        .version("telemetry-estimate", spacecraftId, version, Estimate.class)
        .orElseThrow(() -> ApiException.missing("Estimate version not found"));
  }

  @GetMapping({"/api/telemetry/{spacecraftId}/{id}", "/internal/telemetry/{spacecraftId}/{id}"})
  public Receipt receipt(@PathVariable String spacecraftId, @PathVariable UUID id) {
    return store.require("telemetry-receipt", spacecraftId + ":" + id, Receipt.class).body();
  }
}
