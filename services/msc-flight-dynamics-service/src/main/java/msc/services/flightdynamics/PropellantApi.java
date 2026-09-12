package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import msc.contracts.PropellantContracts.*;
import msc.domain.flightdynamics.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
public class PropellantApi {
  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final msc.ports.Clock clock;

  public PropellantApi(StateStore store, ServiceHttp http, Json json, msc.ports.Clock clock) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  @PostMapping({"/api/propellant-estimates", "/internal/propellant-estimates"})
  public JsonNode estimate(
      @RequestBody Query query,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "propellant-estimate:" + actor.getName();
    var replay = store.replay(scope, key, query);
    if (replay.isPresent()) return replay.get();
    String craft =
        java.net.URLEncoder.encode(query.spacecraftId(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    var modelState =
        http.get("mission-definition", "/internal/propellant-models/" + craft, JsonNode.class);
    var model = json.convert(modelState.get("body"), Model.class);
    var view = http.get("monitoring", "/internal/spacecraft-estimates/" + craft, JsonNode.class);
    var state = view.get("estimate");
    var source = json.convert(state.get("body"), Estimate.class);
    var now = clock.now();
    if (modelState.path("version").asLong() != query.modelVersion()
        || state.path("version").asLong() != query.telemetryVersion())
      throw ApiException.conflict("Propellant source versions changed; recollect inputs");
    if (!model.spacecraftId().equals(query.spacecraftId())
        || !source.binding().spacecraftId().equals(query.spacecraftId())
        || source.binding().environment() != Environment.SIMULATION
        || source.binding().version() != model.telemetryBindingVersion()
        || source.confidence(now) != Confidence.FRESH)
      throw ApiException.invalid("Propellant requires fresh source-bound simulation evidence");
    var frame = source.accepted().orElseThrow().frame();
    var epoch = frame.observedAt();
    var until = epoch.plus(new MissionDuration(model.maximumPropagationSeconds() * 1_000_000_000L));
    if (epoch.compareTo(now) > 0 || until.compareTo(now) < 0)
      throw ApiException.invalid("Propellant model does not cover the current instant");
    String sourceDigest = json.fingerprint(state);
    String id =
        "propellant-"
            + json.fingerprint(Map.of("query", query, "source", state, "model", modelState));
    var estimate =
        new PropellantEstimate(
            new PropellantEstimateId(id),
            new SpacecraftId(query.spacecraftId()),
            new EstimateContext(
                epoch,
                "SCALAR_MASS_KG",
                "absolute-bound-kg:" + model.absoluteUncertaintyKg(),
                "simulation-mass-observation-v1",
                model.missionDefinitionVersion() + ":propellant:" + query.modelVersion(),
                new SnapshotRef(
                    new SnapshotId("monitoring:" + query.spacecraftId()),
                    query.telemetryVersion(),
                    "monitoring-estimate-sha256:" + sourceDigest,
                    now)),
            BigDecimal.valueOf(frame.propellantKg()));
    var result =
        new Snapshot(
            id,
            estimate,
            query.telemetryVersion(),
            source,
            query.modelVersion(),
            model,
            now,
            until);
    return store.idempotent(
        scope,
        key,
        query,
        () -> {
          store.lock("propellant-estimate:" + id);
          return store
              .find("propellant-estimate", id, Snapshot.class)
              .orElseGet(() -> store.create("propellant-estimate", id, result));
        });
  }

  @GetMapping({"/api/propellant-estimates/{id}", "/internal/propellant-estimates/{id}"})
  public Snapshot get(@PathVariable String id) {
    return store.require("propellant-estimate", id, Snapshot.class).body();
  }
}
