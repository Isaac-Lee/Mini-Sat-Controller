package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.TaskingContracts.RequestDetails;
import msc.domain.spacecraftcontrol.CommandLoad;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/** Reviewed V1 simulation release and delivery. No RF or physical spacecraft adapter is used. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class SimulationDispatchApi {
  public record ReleaseRequest(UUID scenarioId, String checksum, String reviewReference) {
    public ReleaseRequest {
      Objects.requireNonNull(scenarioId);
      msc.domain.shared.Checks.text(checksum);
      msc.domain.shared.Checks.text(reviewReference);
    }
  }

  public record Checks(
      CommandScheduleCheckApi.Check schedule,
      CommandAuthorityCheckApi.Check authority,
      JsonNode safety,
      List<JsonNode> decisions) {}

  public record Release(
      String environment,
      UUID scenarioId,
      String reviewer,
      String reviewReference,
      MissionInstant releasedAt,
      CommandCompiler.Prepared prepared,
      Checks checks,
      JsonNode submission) {}

  public record Delivery(
      String status, String reason, MissionInstant recordedAt, JsonNode ledger) {}

  static final String RELEASE = "simulation-command-release";
  static final String DELIVERY = "simulation-command-delivery";
  private final StateStore store;
  private final Json json;
  private final ServiceHttp http;
  private final Clock clock;
  private final CommandScheduleCheckApi schedule;
  private final CommandAuthorityCheckApi authority;

  public SimulationDispatchApi(
      StateStore store,
      Json json,
      ServiceHttp http,
      Clock clock,
      CommandScheduleCheckApi schedule,
      CommandAuthorityCheckApi authority) {
    this.store = store;
    this.json = json;
    this.http = http;
    this.clock = clock;
    this.schedule = schedule;
    this.authority = authority;
  }

  private static String segment(String value) {
    return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
  }

  private String path(Release release) {
    return "/internal/simulation/scenarios/" + release.scenarioId() + "/loads";
  }

  Checks check(String id, CommandCompiler.Prepared prepared) {
    var scheduleCheck = schedule.check(id);
    var authorityCheck = authority.check(id);
    if (!scheduleCheck.reasons().isEmpty() || !authorityCheck.reasons().isEmpty())
      throw ApiException.conflict(
          "Current schedule or authority does not permit simulation delivery");
    String craft = prepared.load().scheduleKey().spacecraftId().value();
    var safety =
        http.post(
            "anomaly",
            "/internal/safety/" + segment(craft) + "/check",
            Map.of(),
            UUID.randomUUID().toString(),
            JsonNode.class);
    if (!safety.path("clear").asBoolean(false)
        || !craft.equals(safety.path("latch").path("spacecraftId").asText()))
      throw ApiException.conflict("Current spacecraft safety latch is not clear");
    var decisions = new ArrayList<JsonNode>();
    for (var assignment : prepared.sources().schedule().assignments()) {
      String requestId = assignment.requestId().value();
      var live =
          http.get("tasking", "/internal/requests/" + segment(requestId), RequestDetails.class);
      if (!requestId.equals(live.request().id().value())
          || live.request().terminal()
          || live.request().deadline().filter(d -> clock.now().compareTo(d) >= 0).isPresent())
        throw ApiException.conflict("Observation request ended or expired");
      var decision =
          http.get(
              "planning",
              "/internal/planning/simulation-schedules/"
                  + segment(requestId)
                  + "/"
                  + live.request().revision(),
              JsonNode.class);
      var body = decision.path("body");
      if (!"SIMULATION".equals(body.path("environment").asText())
          || !"SIMULATION_V1_SAMPLED_REVIEW".equals(body.path("evaluationModel").asText())
          || body.path("requestRevision").asLong() != live.request().revision()
          || !assignment.runId().value().equals(body.path("runId").asText())
          || !assignment.candidateId().value().equals(body.path("candidateId").asText())
          || !json.fingerprint(prepared.sources().schedule())
              .equals(json.fingerprint(body.path("schedule"))))
        throw ApiException.conflict("Schedule does not match the current V1 request decision");
      decisions.add(decision);
    }
    if (decisions.isEmpty())
      throw ApiException.invalid("Request-bound simulation schedule required");
    if (clock.now().compareTo(prepared.load().commitDeadline()) >= 0)
      throw ApiException.conflict("Command deadline passed while checking owners");
    return new Checks(scheduleCheck, authorityCheck, safety, List.copyOf(decisions));
  }

  @PostMapping("/api/command-loads/{id}/simulation-release")
  public JsonNode release(
      @PathVariable String id,
      @RequestBody ReleaseRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-release:" + actor.getName();
    var body = Map.of("loadId", id, "request", request);
    var replay = store.replay(scope, key, body);
    if (replay.isPresent()) return replay.get();
    var prepared =
        store.require("prepared-command-load", id, CommandCompiler.Prepared.class).body();
    if (!prepared.load().checksum().equals(request.checksum()))
      throw ApiException.conflict("Prepared command checksum changed");
    var scenario =
        http.get(
            "simulator", "/internal/simulation/scenarios/" + request.scenarioId(), JsonNode.class);
    var source = scenario.path("body");
    if (!request.scenarioId().toString().equals(scenario.path("id").asText())
        || !"SIMULATION".equals(source.path("environment").asText())
        || !json.fingerprint(prepared.sources().mission())
            .equals(json.fingerprint(source.path("mission")))
        || !json.fingerprint(prepared.sources().correlation())
            .equals(json.fingerprint(source.path("correlation"))))
      throw ApiException.invalid("Scenario and prepared mission/clock differ");
    var checked = check(id, prepared);
    var load = prepared.load();
    var released =
        new CommandLoad(
            load.id(),
            load.scheduleKey(),
            load.scheduleVersion(),
            load.commands(),
            load.missionDefinitionVersion(),
            load.timeCorrelationId(),
            load.checksum(),
            Optional.of("simulation-command-release:" + id),
            load.commitDeadline());
    var catalogs = new TreeMap<String, CatalogReference>();
    var ordered =
        prepared.sources().schedule().activities().stream()
            .sorted(
                Comparator.comparing(
                        (msc.domain.planning.ScheduledActivity a) -> a.window().start())
                    .thenComparing(a -> a.id().value()))
            .toList();
    for (int i = 0; i < ordered.size(); i++) {
      var catalog = prepared.sources().catalogsByActivity().get(ordered.get(i).id().value());
      catalogs.put(
          load.commands().get(i).id().value(),
          new CatalogReference(catalog.id(), catalog.version()));
    }
    var release =
        new Release(
            "SIMULATION",
            request.scenarioId(),
            actor.getName(),
            request.reviewReference(),
            clock.now(),
            prepared,
            checked,
            json.tree(Map.of("load", released, "catalogs", catalogs)));
    return store.idempotent(
        scope,
        key,
        body,
        () -> {
          store.lock("simulation-release:" + id);
          if (store.find(RELEASE, id, Release.class).isPresent())
            throw ApiException.conflict("Load already has a simulation release");
          var saved = store.create(RELEASE, id, release);
          store.event("SimulationCommandReleased", id, 1, UUID.randomUUID(), null, saved);
          return saved;
        });
  }

  private void validateLedger(Release release, JsonNode ledger) {
    String id = release.prepared().load().id().value();
    if (!id.equals(ledger.path("id").asText())
        || ledger.path("version").asLong() < 1
        || !json.fingerprint(ledger.path("body").path("load"))
            .equals(json.fingerprint(release.submission().path("load")))
        || !json.fingerprint(release.submission())
            .equals(ledger.path("body").path("submissionSha256").asText()))
      throw ApiException.invalid("Simulator ledger does not match the released submission");
  }

  private StateStore.State<Delivery> save(String id, Delivery result) {
    return store.transaction(
        () -> {
          store.lock("simulation-delivery:" + id);
          var old = store.find(DELIVERY, id, Delivery.class);
          if (old.isPresent() && "ACCEPTED".equals(old.get().body().status())) return old.get();
          var saved =
              old.isEmpty()
                  ? store.create(DELIVERY, id, result)
                  : store.update(DELIVERY, id, old.get().version(), result);
          store.event(
              "SimulationCommandDeliveryObserved",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return saved;
        });
  }

  @PostMapping("/api/command-loads/{id}/simulation-dispatch")
  public StateStore.State<Delivery> dispatch(@PathVariable String id) {
    var release = store.require(RELEASE, id, Release.class).body();
    var old = store.find(DELIVERY, id, Delivery.class);
    if (old.isPresent() && "ACCEPTED".equals(old.get().body().status())) return old.get();
    // Observe before retrying: a prior timeout or local rollback may hide a successful delivery.
    try {
      var ledger = http.get("simulator", path(release) + "/" + segment(id), JsonNode.class);
      validateLedger(release, ledger);
      return save(id, new Delivery("ACCEPTED", "RECONCILED", clock.now(), ledger));
    } catch (HttpClientErrorException.NotFound absent) {
      // Only an authoritative absence permits a fresh delivery attempt.
    } catch (RestClientException unavailable) {
      return save(id, new Delivery("UNKNOWN", "SIMULATOR_UNAVAILABLE", clock.now(), null));
    }
    check(id, release.prepared());
    // Durable intent precedes outbound I/O; retries use this immutable submission and stable key.
    store.transaction(
        () ->
            store.create(
                "simulation-dispatch-attempt",
                UUID.randomUUID().toString(),
                Map.of(
                    "loadId",
                    id,
                    "scenarioId",
                    release.scenarioId(),
                    "submissionSha256",
                    json.fingerprint(release.submission()),
                    "at",
                    clock.now())));
    try {
      var ledger =
          http.post(
              "simulator", path(release), release.submission(), "control-v1:" + id, JsonNode.class);
      validateLedger(release, ledger);
      return save(id, new Delivery("ACCEPTED", "DELIVERED", clock.now(), ledger));
    } catch (RestClientException unavailable) {
      return save(id, new Delivery("UNKNOWN", "DELIVERY_NOT_CONFIRMED", clock.now(), null));
    }
  }

  @GetMapping({
    "/api/command-loads/{id}/simulation-release",
    "/internal/command-loads/{id}/simulation-release"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public StateStore.State<Release> read(@PathVariable String id) {
    return store.require(RELEASE, id, Release.class);
  }

  @GetMapping("/api/command-loads/{id}/simulation-delivery")
  public StateStore.State<Delivery> delivery(@PathVariable String id) {
    return store.require(DELIVERY, id, Delivery.class);
  }
}
