package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.planning.*;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

@RestController
public class CommandPreparationApi {
  @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class)
  @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
  public ApiErrors.Error missingOwnerArtifact() {
    return new ApiErrors.Error("NOT_FOUND", "Required owner artifact not found");
  }

  public record Prepare(
      CommandLoadId id,
      ScheduleKey schedule,
      long scheduleVersion,
      long correlationVersion,
      Map<String, CatalogReference> catalogsByActivity,
      Map<String, Map<String, String>> parametersByActivity,
      MissionInstant deadline) {
    public Prepare {
      Objects.requireNonNull(id);
      Objects.requireNonNull(schedule);
      Objects.requireNonNull(deadline).requireTai();
      catalogsByActivity = Map.copyOf(catalogsByActivity);
      var copy = new TreeMap<String, Map<String, String>>();
      parametersByActivity.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
      parametersByActivity = Collections.unmodifiableMap(copy);
      if (scheduleVersion <= 0
          || correlationVersion <= 0
          || catalogsByActivity.isEmpty()
          || catalogsByActivity.size() > 100
          || !catalogsByActivity.keySet().equals(parametersByActivity.keySet()))
        throw new IllegalArgumentException(
            "Bounded exact schedule/catalog/correlation inputs required");
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public CommandPreparationApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  @PostMapping("/api/command-loads/prepare")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public JsonNode prepare(
      @RequestBody Prepare request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "command-prepare:" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var snapshot =
        http.post(
            "planning",
            "/internal/planning/schedules/query",
            Map.of("key", request.schedule(), "version", request.scheduleVersion()),
            key,
            MissionSchedule.Snapshot.class);
    if (!snapshot.key().equals(request.schedule())
        || snapshot.version() != request.scheduleVersion())
      throw ApiException.invalid("Planning returned a different schedule identity/version");
    String craft = request.schedule().spacecraftId().value();
    String craftPath = UriUtils.encodePathSegment(craft, StandardCharsets.UTF_8);
    var mission =
        http.get("mission-definition", "/internal/missions/" + craftPath, MissionProfile.class);
    var envelope =
        http.get(
            "mission-definition",
            "/internal/simulation-time-correlations/"
                + craftPath
                + "/versions/"
                + request.correlationVersion(),
            JsonNode.class);
    if (!envelope.path("id").asText().equals(craft)
        || !envelope.path("version").isIntegralNumber()
        || !envelope.path("version").canConvertToLong()
        || envelope.path("version").longValue() != request.correlationVersion())
      throw ApiException.invalid("Mission Definition returned a different correlation version");
    var correlation =
        new StateStore.State<>(
            craft,
            request.correlationVersion(),
            json.convert(envelope.get("body"), Correlation.class));
    var catalogs = new TreeMap<String, CatalogEntry>();
    request
        .catalogsByActivity()
        .forEach(
            (activity, ref) -> {
              var catalog =
                  http.get(
                      "mission-definition",
                      "/internal/catalog/"
                          + UriUtils.encodePathSegment(ref.catalogId(), StandardCharsets.UTF_8)
                          + "/versions/"
                          + ref.catalogVersion(),
                      CatalogEntry.class);
              if (!catalog.id().equals(ref.catalogId())
                  || catalog.version() != ref.catalogVersion())
                throw ApiException.invalid(
                    "Mission Definition returned a different catalog version");
              catalogs.put(activity, catalog);
            });
    CommandCompiler.Prepared prepared;
    try {
      prepared =
          new CommandCompiler(json)
              .compile(
                  request.id(),
                  new CommandCompiler.Sources(
                      snapshot,
                      mission,
                      correlation,
                      catalogs,
                      request.parametersByActivity(),
                      request.deadline()));
    } catch (ArithmeticException invalidTime) {
      throw ApiException.invalid("Schedule cannot be represented exactly in onboard time");
    }
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("prepared-command-load:" + request.id().value());
          if (store
              .find("prepared-command-load", request.id().value(), CommandCompiler.Prepared.class)
              .isPresent()) throw ApiException.conflict("Command load identity already prepared");
          var saved = store.create("prepared-command-load", request.id().value(), prepared);
          store.event(
              "CommandLoadPrepared", saved.id(), saved.version(), UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  @GetMapping({"/api/command-loads/{id}", "/internal/command-loads/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<CommandCompiler.Prepared> read(@PathVariable String id) {
    return store.require("prepared-command-load", id, CommandCompiler.Prepared.class);
  }
}
