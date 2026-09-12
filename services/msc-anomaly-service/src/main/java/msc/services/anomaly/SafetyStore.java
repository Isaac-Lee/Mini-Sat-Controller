package msc.services.anomaly;

import java.util.*;
import msc.domain.anomaly.*;
import msc.domain.anomaly.SafetyPolicy.Reason;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public final class SafetyStore {
  private final StateStore store;
  private final msc.ports.Clock clock;
  private final Json json;

  public SafetyStore(StateStore store, msc.ports.Clock clock, Json json) {
    this.store = store;
    this.clock = clock;
    this.json = json;
  }

  public StateStore.State<SafetyLatch> configure(SafetyPolicy policy) {
    store.lock("safety:" + policy.spacecraftId());
    var prior = store.find("safety-policy", policy.spacecraftId(), SafetyPolicy.class);
    if (prior.isPresent() && prior.get().body().equals(policy)) return state(policy.spacecraftId());
    if (policy.version() != (prior.isEmpty() ? 1 : prior.get().body().version() + 1))
      throw ApiException.conflict("Safety policy version changed");
    if (prior.isEmpty()) store.create("safety-policy", policy.spacecraftId(), policy);
    else store.update("safety-policy", policy.spacecraftId(), prior.get().version(), policy);
    var old = store.find("safety-latch", policy.spacecraftId(), SafetyLatch.class);
    var initial = SafetyLatch.initial(policy);
    var result =
        old.isEmpty()
            ? store.create("safety-latch", policy.spacecraftId(), initial)
            : store.update("safety-latch", policy.spacecraftId(), old.get().version(), initial);
    store.event("PlanningFrozen", result.id(), result.version(), UUID.randomUUID(), null, result);
    return result;
  }

  public SafetyPolicy policy(String craft) {
    return store.require("safety-policy", craft, SafetyPolicy.class).body();
  }

  public StateStore.State<SafetyLatch> state(String craft) {
    return store.require("safety-latch", craft, SafetyLatch.class);
  }

  public void observe(SafetyEvidence.Reading reading, UUID correlation, UUID causation) {
    store.lock("safety:" + reading.spacecraftId());
    var old = store.find("safety-evidence", reading.spacecraftId(), SafetyEvidence.Reading.class);
    if (old.isEmpty()) store.create("safety-evidence", reading.spacecraftId(), reading);
    else if (reading.version() > old.get().body().version())
      store.update("safety-evidence", reading.spacecraftId(), old.get().version(), reading);
    else if (reading.version() == old.get().body().version() && !reading.equals(old.get().body()))
      throw ApiException.conflict("Monitoring version content conflict");
    var configured = store.find("safety-policy", reading.spacecraftId(), SafetyPolicy.class);
    if (configured.isEmpty()) return;
    // Late critical evidence is still an incident. Do not lose a transient SAFE mode merely
    // because a newer nominal projection arrived first. Inbox/fact identities prevent replay.
    var policy = configured.get().body();
    var reasons = new java.util.HashSet<>(policy.evaluate(reading.estimate(), clock.now()));
    boolean historical = old.isPresent() && reading.version() < old.get().body().version();
    if (historical) {
      if (reading.estimate().binding().version() != policy.telemetryBindingVersion())
        reasons.clear();
      reasons.removeAll(Set.of(Reason.STALE_STATE, Reason.UNKNOWN_STATE, Reason.DEGRADED_STATE));
    }
    if (!reasons.isEmpty())
      freeze(
          reading.spacecraftId(),
          reasons,
          evidenceKey(reading, reasons),
          correlation,
          causation,
          true);
  }

  public StateStore.State<SafetyLatch> freeze(
      String craft,
      Set<Reason> reasons,
      String evidence,
      UUID correlation,
      UUID causation,
      boolean onlyNew) {
    var old = state(craft);
    String fact =
        craft
            + ":"
            + old.body().policyVersion()
            + ":"
            + json.fingerprint(
                Map.of(
                    "evidence",
                    evidence,
                    "reasons",
                    reasons.stream().map(Enum::name).sorted().toList()));
    boolean fresh = store.find("safety-fact", fact, String.class).isEmpty();
    if (onlyNew && !fresh) return old;
    var next = old.body().freeze(reasons, fresh);
    if (fresh) {
      store.create("safety-fact", fact, evidence);
      String id = UUID.randomUUID().toString();
      var anomaly =
          new Anomaly(
              new msc.domain.shared.Ids.AnomalyId(id),
              new msc.domain.shared.Ids.SpacecraftId(craft),
              Anomaly.Severity.CRITICAL,
              clock.now(),
              "monitoring:" + craft + ":" + evidence);
      var incident =
          new Incident(anomaly, next.policyVersion(), next.generation(), Set.copyOf(reasons));
      store.create("anomaly", id, incident);
      store.event("AnomalyDeclared", id, 1, correlation, causation, incident);
    }
    if (next.equals(old.body())) return old;
    var saved = store.update("safety-latch", craft, old.version(), next);
    store.event("PlanningFrozen", craft, saved.version(), correlation, causation, saved);
    return saved;
  }

  private static String evidenceKey(SafetyEvidence.Reading reading, Set<Reason> reasons) {
    if (Set.of(Reason.SAFE_MODE, Reason.LOW_BATTERY, Reason.STORAGE_LIMIT, Reason.LOW_PROPELLANT)
        .containsAll(reasons))
      return "frame:" + reading.estimate().accepted().orElseThrow().frame().id();
    return "projection:" + reading.version();
  }

  public record Incident(
      Anomaly anomaly, long policyVersion, long generation, Set<Reason> reasons) {
    public Incident {
      reasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, reasons);
    }
  }

  public record Check(
      boolean clear,
      long safetyVersion,
      long policyVersion,
      long monitoringVersion,
      MissionInstant evaluatedAt,
      Set<Reason> currentReasons,
      SafetyLatch latch) {
    public Check {
      currentReasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, currentReasons);
    }
  }

  public Check check(String craft, Optional<SafetyEvidence.Reading> reading) {
    store.lock("safety:" + craft);
    var policy = policy(craft);
    var now = clock.now();
    var cached = store.find("safety-evidence", craft, SafetyEvidence.Reading.class);
    if (reading.isPresent() && !reading.get().spacecraftId().equals(craft))
      throw ApiException.invalid("Wrong spacecraft evidence");
    if (reading.isPresent()
        && cached.isPresent()
        && reading.get().version() < cached.get().body().version())
      throw ApiException.conflict("Monitoring evidence advanced; retry safety check");
    Set<Reason> reasons =
        reading.isPresent()
            ? policy.evaluate(reading.get().estimate(), now)
            : Set.of(Reason.MONITORING_UNAVAILABLE);
    reading.ifPresent(r -> observe(r, UUID.randomUUID(), null));
    var latch =
        reasons.isEmpty()
            ? state(craft)
            : freeze(
                craft,
                reasons,
                reading.map(r -> evidenceKey(r, reasons)).orElse("monitoring-unavailable"),
                UUID.randomUUID(),
                null,
                false);
    return new Check(
        reasons.isEmpty() && !latch.body().frozen(),
        latch.version(),
        policy.version(),
        reading.map(SafetyEvidence.Reading::version).orElse(0L),
        now,
        reasons,
        latch.body());
  }

  public StateStore.State<SafetyLatch> approve(
      String craft, long expected, String actor, String decision) {
    var prior = state(craft);
    if (prior.version() != expected) throw ApiException.conflict("Safety state changed");
    var next = prior.body().approve(policy(craft), actor, decision, clock.now());
    if (prior.body().equals(next)) return prior;
    var saved = store.update("safety-latch", craft, prior.version(), next);
    if (!next.frozen())
      store.create(
          "safety-resolution", craft + ":" + next.policyVersion() + ":" + next.generation(), saved);
    store.event(
        next.frozen() ? "SafetyRecoveryApprovalRecorded" : "PlanningFreezeCleared",
        craft,
        saved.version(),
        UUID.randomUUID(),
        null,
        saved);
    return saved;
  }
}
