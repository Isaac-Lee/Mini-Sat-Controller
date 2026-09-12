package msc.services.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.anomaly.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN','SERVICE')")
public class SafetyApi {
  public record Recovery(long expectedSafetyVersion, String decisionReference) {
    public Recovery {
      if (expectedSafetyVersion < 1) throw ApiException.invalid("Expected safety version required");
      msc.domain.shared.Checks.text(decisionReference);
    }
  }

  private final StateStore store;
  private final SafetyStore safety;
  private final SafetyEvidence evidence;

  public SafetyApi(StateStore store, SafetyStore safety, SafetyEvidence evidence) {
    this.store = store;
    this.safety = safety;
    this.evidence = evidence;
  }

  @PostMapping("/api/safety-policies")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode configure(
      @RequestBody SafetyPolicy policy,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "safety-policy:" + actor.getName(), key, policy, () -> safety.configure(policy));
  }

  @GetMapping({"/api/safety/{craft}", "/internal/safety/{craft}"})
  public StateStore.State<SafetyLatch> state(@PathVariable String craft) {
    return safety.state(craft);
  }

  @GetMapping("/api/safety/{craft}/history")
  public List<StateStore.State<JsonNode>> history(@PathVariable String craft) {
    return store.history("safety-latch", craft);
  }

  public record IncidentView(SafetyStore.Incident incident, Optional<JsonNode> resolution) {}

  @GetMapping("/api/anomalies")
  public List<StateStore.State<JsonNode>> incidents(@RequestParam(defaultValue = "100") int limit) {
    return store.list("anomaly", limit);
  }

  @GetMapping("/api/anomalies/{id}")
  public IncidentView incident(@PathVariable String id) {
    var incident = store.require("anomaly", id, SafetyStore.Incident.class).body();
    String resolutionKey =
        incident.anomaly().spacecraftId().value()
            + ":"
            + incident.policyVersion()
            + ":"
            + incident.generation();
    return new IncidentView(
        incident,
        store.find("safety-resolution", resolutionKey, JsonNode.class).map(StateStore.State::body));
  }

  Optional<SafetyEvidence.Reading> current(String craft) {
    safety.policy(craft); // validates configured, bounded path identity before the remote call
    try {
      return Optional.of(evidence.current(craft));
    } catch (org.springframework.web.client.RestClientException
        | IllegalArgumentException failure) {
      return Optional.empty();
    }
  }

  // Intentionally re-evaluated on every call, never replay a formerly clear decision.
  @PostMapping({"/internal/safety/{craft}/check", "/api/safety/{craft}/check"})
  @PreAuthorize("hasAnyRole('SERVICE','OPERATOR','ADMIN')")
  public SafetyStore.Check check(@PathVariable String craft) {
    var reading = current(craft);
    return store.transaction(() -> safety.check(craft, reading));
  }

  @PostMapping("/api/safety/{craft}/recovery-approvals")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public JsonNode recover(
      @PathVariable String craft,
      @RequestBody Recovery request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    var bound = Map.of("spacecraftId", craft, "request", request);
    String scope = "safety-recovery:" + actor.getName();
    var replay = store.replay(scope, key, bound);
    if (replay.isPresent()) return replay.get();
    var reading = current(craft);
    return store.idempotent(
        scope,
        key,
        bound,
        () -> {
          var checked = safety.check(craft, reading);
          if (!checked.currentReasons().isEmpty())
            return Map.of("approved", false, "check", checked);
          var updated =
              safety.approve(
                  craft,
                  request.expectedSafetyVersion(),
                  actor.getName(),
                  request.decisionReference());
          return Map.of("approved", true, "state", updated);
        });
  }
}
