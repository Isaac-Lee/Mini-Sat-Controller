package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.MissionCatalogBindingContracts.CatalogReference;
import msc.contracts.OperationResourceContracts.OperationResourceProfile;
import msc.domain.spacecraftcontrol.*;
import msc.platform.*;
import msc.services.simulator.SimulationScenarioApi.Scenario;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/** Onboard simulation facts only. SERVICE authentication is not Control release authorization. */
@RestController
public class SimulationCommandApi {
  public record Submit(CommandLoad load, Map<String, CatalogReference> catalogs) {
    public Submit {
      Objects.requireNonNull(load);
      catalogs = Map.copyOf(catalogs);
      if (load.commands().size() > 100 || catalogs.size() != load.commands().size())
        throw new IllegalArgumentException(
            "One catalog per command, at most 100 commands per load");
    }
  }

  public enum Status {
    PENDING,
    EFFECT_APPLIED,
    REJECTED,
    NOT_SUPPORTED
  }

  public record Entry(
      CommandInstance command,
      CatalogEntry catalog,
      String catalogSha256,
      OperationResourceProfile profile,
      long startTick,
      long completionTick,
      Status status,
      SimulatorOperationEffects.Result effect) {}

  public record Ledger(CommandLoad load, String submissionSha256, List<Entry> entries) {
    public Ledger {
      entries = List.copyOf(entries);
    }
  }

  public record Advance(long expectedVersion, long targetTick) {
    public Advance {
      if (expectedVersion <= 0 || targetTick < 0)
        throw new IllegalArgumentException("Invalid clock advance");
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public SimulationCommandApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  private static String kind(UUID scenario) {
    return "simulation-load:" + scenario;
  }

  private StateStore.State<Scenario> scenario(UUID id) {
    return store.require("simulation-scenario", id.toString(), Scenario.class);
  }

  private JsonNode original(UUID scenario, String loadId, String hash) {
    var existing = store.find(kind(scenario), loadId, Ledger.class);
    if (existing.isEmpty()) return null;
    if (!existing.get().body().submissionSha256().equals(hash))
      throw ApiException.conflict("Command load ID reused with different content");
    return json.tree(store.version(kind(scenario), loadId, 1, Ledger.class).orElseThrow());
  }

  @PostMapping("/internal/simulation/scenarios/{id}/loads")
  @PreAuthorize("hasRole('SERVICE')")
  public JsonNode submit(
      @PathVariable UUID id,
      @RequestBody Submit request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "simulation-load:" + id + ":" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    String loadId = request.load().id().value();
    String hash = json.fingerprint(request);
    var original = original(id, loadId, hash);
    if (original != null) return store.idempotent(scope, key, request, () -> original);
    var snapshot = scenario(id).body();
    var load = request.load();
    if (!load.scheduleKey().spacecraftId().value().equals(snapshot.mission().spacecraftId())
        || !load.missionDefinitionVersion().equals(snapshot.mission().missionDefinitionVersion())
        || !load.timeCorrelationId()
            .value()
            .equals(snapshot.correlation().body().timeCorrelationId()))
      throw ApiException.invalid("Command load does not match pinned scenario");
    var entries = new ArrayList<Entry>();
    for (var command : load.commands()) {
      var reference = request.catalogs().get(command.id().value());
      if (reference == null) throw ApiException.invalid("Command catalog binding missing");
      var profile =
          snapshot.resourceProfiles().body().profiles().stream()
              .filter(p -> p.catalog().equals(reference))
              .findFirst()
              .orElseThrow(
                  () -> ApiException.invalid("Catalog absent from pinned operation profiles"));
      String path = UriUtils.encodePathSegment(reference.catalogId(), StandardCharsets.UTF_8);
      var catalog =
          http.get(
              "mission-definition",
              "/internal/catalog/" + path + "/versions/" + reference.catalogVersion(),
              CatalogEntry.class);
      SimulatorOperationEffects.requireMatchingProfile(catalog, profile);
      if (!command
          .templateReference()
          .equals(catalog.template().id() + ":" + catalog.template().version()))
        throw ApiException.invalid("Command template does not match pinned catalog");
      catalog.template().validate(command.parameters());
      try {
        var correlation = snapshot.correlation().body();
        var tag = command.timeTag();
        var start =
            correlation.tickToTai(tag.correlationId().value(), tag.clockPartition(), tag.ticks());
        // Decimal catalog duration must map exactly to ticks; no rounding or silent overflow.
        long ticks =
            BigDecimal.valueOf(catalog.durationSeconds())
                .multiply(BigDecimal.valueOf(correlation.ticksPerSecond()))
                .longValueExact();
        if (ticks <= 0)
          throw ApiException.invalid("Command duration is not a positive whole tick count");
        long completion = Math.addExact(tag.ticks(), ticks);
        var end =
            correlation.tickToTai(tag.correlationId().value(), tag.clockPartition(), completion);
        if (start.compareTo(load.scheduleKey().horizon().start()) < 0
            || end.compareTo(load.scheduleKey().horizon().end()) > 0)
          throw ApiException.invalid("Command interval lies outside load horizon");
        entries.add(
            new Entry(
                command,
                catalog,
                json.fingerprint(catalog),
                profile,
                tag.ticks(),
                completion,
                Status.PENDING,
                null));
      } catch (ArithmeticException invalidTime) {
        throw ApiException.invalid(
            "Command duration/time cannot be represented exactly by the pinned clock");
      }
    }
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("simulation-scenario:" + id);
          var prior = original(id, loadId, hash);
          if (prior != null) return prior;
          var current = scenario(id).body();
          var c = current.correlation().body();
          if (c.tickToTai(c.timeCorrelationId(), c.clockPartition(), current.currentTick())
                  .compareTo(load.commitDeadline())
              >= 0) throw ApiException.conflict("Load commit deadline passed in simulation time");
          var all = ledgers(id);
          int total = all.stream().mapToInt(s -> s.body().entries().size()).sum();
          if (total + entries.size() > 500)
            throw ApiException.conflict("Scenario command limit reached; create another scenario");
          var occupied = new ArrayList<Entry>();
          all.forEach(s -> occupied.addAll(s.body().entries()));
          for (var entry : entries) {
            if (entry.startTick() < current.currentTick())
              throw ApiException.conflict("Cannot load a command in the simulation past");
            for (var other : occupied) {
              if (entry.command().id().equals(other.command().id()))
                throw ApiException.conflict("Command ID already exists in scenario");
              // Explicit single-activity simulation policy; no implicit hardware concurrency.
              if (entry.startTick() < other.completionTick()
                  && other.startTick() < entry.completionTick())
                throw ApiException.conflict(
                    "Overlapping commands are not supported in this scenario model");
            }
            occupied.add(entry);
          }
          // No ground-observable event here: a received load is not an execution observation.
          return store.create(kind(id), loadId, new Ledger(load, hash, entries));
        });
  }

  private List<StateStore.State<Ledger>> ledgers(UUID id) {
    return store.list(kind(id), 500).stream()
        .map(s -> new StateStore.State<>(s.id(), s.version(), json.convert(s.body(), Ledger.class)))
        .toList();
  }

  @PostMapping("/api/simulation/scenarios/{id}/advance")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode advance(
      @PathVariable UUID id,
      @RequestBody Advance request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-advance:" + id + ":" + actor.getName(),
        key,
        request,
        () -> {
          store.lock("simulation-scenario:" + id);
          var saved = scenario(id);
          var before = saved.body();
          if (saved.version() != request.expectedVersion())
            throw ApiException.conflict("Scenario version changed");
          if (request.targetTick() < before.currentTick())
            throw ApiException.conflict("Clock cannot move backwards");
          var correlation = before.correlation().body();
          try {
            correlation.tickToTai(
                correlation.timeCorrelationId(),
                correlation.clockPartition(),
                request.targetTick());
          } catch (ArithmeticException invalidTime) {
            throw ApiException.invalid("Target tick exceeds pinned clock arithmetic range");
          }
          record Due(String loadId, int index, Entry entry) {}
          var all = ledgers(id);
          var due = new ArrayList<Due>();
          for (var load : all)
            for (int i = 0; i < load.body().entries().size(); i++) {
              var entry = load.body().entries().get(i);
              if (entry.status() == Status.PENDING
                  && entry.completionTick() <= request.targetTick())
                due.add(new Due(load.id(), i, entry));
            }
          due.sort(
              Comparator.comparingLong((Due d) -> d.entry().completionTick())
                  .thenComparing(Due::loadId)
                  .thenComparing(d -> d.entry().command().id().value()));
          var changed = new HashMap<String, List<Entry>>();
          var reservoirs = before.reservoirs();
          for (var d : due) {
            var e = d.entry();
            var effect =
                SimulatorOperationEffects.complete(
                    before.mission(), reservoirs, e.catalog(), e.profile());
            var status =
                switch (effect.outcome()) {
                  case APPLIED -> Status.EFFECT_APPLIED;
                  case NOT_SUPPORTED -> Status.NOT_SUPPORTED;
                  default -> Status.REJECTED;
                };
            var entries =
                changed.computeIfAbsent(
                    d.loadId(),
                    keyId ->
                        new ArrayList<>(
                            all.stream()
                                .filter(l -> l.id().equals(keyId))
                                .findFirst()
                                .orElseThrow()
                                .body()
                                .entries()));
            entries.set(
                d.index(),
                new Entry(
                    e.command(),
                    e.catalog(),
                    e.catalogSha256(),
                    e.profile(),
                    e.startTick(),
                    e.completionTick(),
                    status,
                    effect));
            if (status == Status.EFFECT_APPLIED
                && e.profile().operation()
                    == msc.contracts.OperationResourceContracts.Operation.IMAGE)
              SimulationPayload.record(store, json, id, e);
            reservoirs = effect.after();
          }
          for (var load : all)
            if (changed.containsKey(load.id()))
              store.update(
                  kind(id),
                  load.id(),
                  load.version(),
                  new Ledger(
                      load.body().load(), load.body().submissionSha256(), changed.get(load.id())));
          var after =
              new Scenario(
                  before.id(),
                  before.environment(),
                  before.mission(),
                  before.missionSha256(),
                  before.correlation(),
                  before.correlationSha256(),
                  before.resourceProfiles(),
                  before.resourceProfilesSha256(),
                  request.targetTick(),
                  reservoirs,
                  before.provenance());
          // Effects and clock/ledger revisions commit together. Ground delivery is a separate step.
          return store.update("simulation-scenario", id.toString(), saved.version(), after);
        });
  }

  @GetMapping("/internal/simulation/scenarios/{id}/loads/{loadId}")
  @PreAuthorize("hasRole('SERVICE')")
  public StateStore.State<Ledger> read(@PathVariable UUID id, @PathVariable String loadId) {
    scenario(id);
    return store.require(kind(id), loadId, Ledger.class);
  }
}
