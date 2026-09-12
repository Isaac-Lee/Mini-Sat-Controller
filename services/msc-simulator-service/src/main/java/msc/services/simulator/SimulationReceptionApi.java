package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.SimulationExecutionContracts.*;
import msc.platform.*;
import msc.services.simulator.SimulationCommandApi.Ledger;
import msc.services.simulator.SimulationScenarioApi.Scenario;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit simulated delivery attempts; execution alone never publishes a ground observation. */
@RestController
public class SimulationReceptionApi {
  public record Configure(
      long expectedVersion,
      boolean connected,
      boolean acknowledgmentLost,
      long notBeforeTick,
      String provenance) {
    public Configure {
      if (expectedVersion < 0 || notBeforeTick < 0)
        throw new IllegalArgumentException("Invalid link configuration");
      msc.domain.shared.Checks.text(provenance);
    }
  }

  public enum Channel {
    ACKNOWLEDGMENT,
    RECONCILIATION
  }

  public record Attempt(Channel channel) {
    public Attempt {
      Objects.requireNonNull(channel);
    }
  }

  public enum Belief {
    UNKNOWN,
    OBSERVED
  }

  public record Reception(Belief belief, String reason, Optional<Observation> observation) {}

  private final StateStore store;
  private final Json json;

  public SimulationReceptionApi(StateStore store, Json json) {
    this.store = store;
    this.json = json;
  }

  private static String policyKind(UUID id) {
    return "simulation-link:" + id;
  }

  private static String observationKind(UUID id) {
    return "simulation-observation:" + id;
  }

  @PostMapping("/api/simulation/scenarios/{id}/loads/{loadId}/link")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode configure(
      @PathVariable UUID id,
      @PathVariable String loadId,
      @RequestBody Configure request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-link:" + id + ":" + loadId + ":" + actor.getName(),
        key,
        request,
        () -> {
          store.lock("simulation-scenario:" + id);
          store.require("simulation-scenario", id.toString(), Scenario.class);
          store.require("simulation-load:" + id, loadId, Ledger.class);
          var old = store.find(policyKind(id), loadId, Configure.class);
          if (request.expectedVersion() != old.map(StateStore.State::version).orElse(0L))
            throw ApiException.conflict("Simulation link version changed");
          return old.isEmpty()
              ? store.create(policyKind(id), loadId, request)
              : store.update(policyKind(id), loadId, request.expectedVersion(), request);
        });
  }

  @PostMapping("/internal/simulation/scenarios/{id}/loads/{loadId}/receive")
  @PreAuthorize("hasRole('SERVICE')")
  public JsonNode receive(
      @PathVariable UUID id,
      @PathVariable String loadId,
      @RequestBody Attempt request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-receive:" + id + ":" + loadId + ":" + actor.getName(),
        key,
        request,
        () -> {
          store.lock("simulation-scenario:" + id);
          var scenario = store.require("simulation-scenario", id.toString(), Scenario.class).body();
          var ledger = store.require("simulation-load:" + id, loadId, Ledger.class);
          var prior = store.find(observationKind(id), loadId, Observation.class);
          // Already received evidence is not erased by a later disconnection or policy change.
          if (prior.isPresent())
            return new Reception(
                Belief.OBSERVED, "ALREADY_RECEIVED", Optional.of(prior.get().body()));
          var policy = store.find(policyKind(id), loadId, Configure.class);
          if (policy.isEmpty()) return unknown("LINK_NOT_CONFIGURED");
          if (!policy.get().body().connected()) return unknown("DISCONNECTED");
          if (scenario.currentTick() < policy.get().body().notBeforeTick())
            return unknown("DELAYED");
          if (request.channel() == Channel.ACKNOWLEDGMENT
              && policy.get().body().acknowledgmentLost()) return unknown("ACKNOWLEDGMENT_LOST");
          if (ledger.body().entries().stream()
              .anyMatch(e -> e.status() == SimulationCommandApi.Status.PENDING))
            return unknown("EXECUTION_NOT_OBSERVED");
          var correlation = scenario.correlation().body();
          long completed =
              ledger.body().entries().stream()
                  .mapToLong(SimulationCommandApi.Entry::completionTick)
                  .max()
                  .orElseThrow();
          var commands =
              ledger.body().entries().stream()
                  .map(
                      e ->
                          new CommandEvidence(
                              e.command().id().value(),
                              ModeledOutcome.valueOf(e.status().name()),
                              e.completionTick(),
                              e.catalogSha256()))
                  .toList();
          var observation =
              new Observation(
                  id,
                  scenario.mission().spacecraftId(),
                  loadId,
                  "SIMULATION",
                  ledger.version(),
                  json.fingerprint(ledger),
                  commands,
                  correlation.tickToTai(
                      correlation.timeCorrelationId(), correlation.clockPartition(), completed),
                  correlation.tickToTai(
                      correlation.timeCorrelationId(),
                      correlation.clockPartition(),
                      scenario.currentTick()));
          var saved = store.create(observationKind(id), loadId, observation);
          store.event(
              "SpacecraftExecutionObserved",
              observation.spacecraftId(),
              saved.version(),
              UUID.randomUUID(),
              null,
              observation);
          return new Reception(Belief.OBSERVED, "RECEIVED", Optional.of(observation));
        });
  }

  private static Reception unknown(String reason) {
    return new Reception(Belief.UNKNOWN, reason, Optional.empty());
  }
}
