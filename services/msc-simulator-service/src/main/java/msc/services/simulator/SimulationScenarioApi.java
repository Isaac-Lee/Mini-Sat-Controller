package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import msc.contracts.CatalogContracts.MissionProfile;
import msc.contracts.OperationResourceContracts.Profiles;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.platform.*;
import msc.services.simulator.SimulatorOperationEffects.Reservoirs;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/** Durable, explicitly simulated initial state; creation grants no command release authority. */
@RestController
public class SimulationScenarioApi {
  public record Create(
      UUID id,
      String spacecraftId,
      long correlationVersion,
      long resourceProfilesVersion,
      long initialTick,
      Reservoirs reservoirs,
      String provenance) {
    public Create {
      Objects.requireNonNull(id);
      msc.domain.shared.Checks.text(spacecraftId);
      msc.domain.shared.Checks.text(provenance);
      Objects.requireNonNull(reservoirs);
      if (correlationVersion <= 0 || resourceProfilesVersion <= 0 || initialTick < 0)
        throw new IllegalArgumentException("Exact positive versions and nonnegative tick required");
    }
  }

  public record Scenario(
      UUID id,
      String environment,
      MissionProfile mission,
      String missionSha256,
      StateStore.State<Correlation> correlation,
      String correlationSha256,
      StateStore.State<Profiles> resourceProfiles,
      String resourceProfilesSha256,
      long currentTick,
      Reservoirs reservoirs,
      String provenance) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public SimulationScenarioApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  @PostMapping("/api/simulation/scenarios")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode create(
      @RequestBody Create request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-scenario:" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    if (store.find("simulation-scenario", request.id().toString(), Scenario.class).isPresent())
      return store.idempotent(
          scope,
          key,
          request,
          () -> {
            throw ApiException.conflict("Simulation scenario already exists");
          });
    String craftPath = UriUtils.encodePathSegment(request.spacecraftId(), StandardCharsets.UTF_8);
    // MissionProfile is create-only in its owner. Retain its complete content and hash rather
    // than inventing an owner version number that this endpoint does not publish.
    var mission =
        http.get("mission-definition", "/internal/missions/" + craftPath, MissionProfile.class);
    var correlation =
        exact(
            "/internal/simulation-time-correlations/" + craftPath,
            request.spacecraftId(),
            request.correlationVersion(),
            Correlation.class);
    var profiles =
        exact(
            "/internal/operation-resource-profiles/" + craftPath,
            request.spacecraftId(),
            request.resourceProfilesVersion(),
            Profiles.class);
    if (!request.spacecraftId().equals(mission.spacecraftId())
        || !request.spacecraftId().equals(correlation.body().spacecraftId())
        || !request.spacecraftId().equals(profiles.body().spacecraftId())
        || !mission.missionDefinitionVersion().equals(correlation.body().missionDefinitionVersion())
        || !mission.missionDefinitionVersion().equals(profiles.body().missionDefinitionVersion())
        || !mission.timeCorrelationId().equals(correlation.body().timeCorrelationId()))
      throw ApiException.invalid("Simulation source bindings do not match the mission");
    correlation
        .body()
        .tickToTai(
            mission.timeCorrelationId(),
            correlation.body().clockPartition(),
            request.initialTick());
    if (request.reservoirs().storedMegabytes() > mission.storageCapacityMb()
        || request.reservoirs().propellantKilograms() > mission.propellantKg())
      throw ApiException.invalid("Initial reservoirs exceed mission capacity");
    var scenario =
        new Scenario(
            request.id(),
            "SIMULATION",
            mission,
            json.fingerprint(mission),
            correlation,
            json.fingerprint(correlation),
            profiles,
            json.fingerprint(profiles),
            request.initialTick(),
            request.reservoirs(),
            request.provenance());
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = request.id().toString();
          store.lock("simulation-scenario:" + id);
          if (store.find("simulation-scenario", id, Scenario.class).isPresent())
            throw ApiException.conflict("Simulation scenario already exists");
          var saved = store.create("simulation-scenario", id, scenario);
          store.event(
              "SimulationScenarioCreated", id, saved.version(), UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  private <T> StateStore.State<T> exact(String base, String id, long version, Class<T> type) {
    var envelope = http.get("mission-definition", base + "/versions/" + version, JsonNode.class);
    if (!envelope.path("id").isTextual()
        || !id.equals(envelope.path("id").asText())
        || !envelope.path("version").isIntegralNumber()
        || !envelope.path("version").canConvertToLong()
        || envelope.path("version").longValue() != version
        || !envelope.path("body").isObject())
      throw ApiException.invalid("Exact simulation source version not returned by owner");
    return new StateStore.State<>(id, version, json.convert(envelope.get("body"), type));
  }

  /** Diagnostic onboard state, not a ground observation or successful command acknowledgment. */
  @GetMapping({"/api/simulation/scenarios/{id}", "/internal/simulation/scenarios/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
  public StateStore.State<Scenario> read(@PathVariable UUID id) {
    return store.require("simulation-scenario", id.toString(), Scenario.class);
  }
}
