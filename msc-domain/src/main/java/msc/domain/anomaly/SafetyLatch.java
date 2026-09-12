package msc.domain.anomaly;

import java.util.*;
import msc.domain.anomaly.SafetyPolicy.Reason;
import msc.domain.time.*;

/** A critical incident stays latched until scoped, fresh, distinct operator approvals clear it. */
public record SafetyLatch(
    String spacecraftId,
    long policyVersion,
    long generation,
    boolean frozen,
    Set<Reason> reasons,
    List<Approval> approvals) {
  public record Approval(
      String actor,
      String decisionReference,
      MissionInstant grantedAt,
      MissionInstant validUntil,
      long generation) {
    public Approval {
      msc.domain.shared.Checks.text(actor);
      msc.domain.shared.Checks.text(decisionReference);
      grantedAt.requireTai();
      validUntil.requireTai();
    }
  }

  public SafetyLatch {
    // Canonical, JVM-stable Set order: see Checks.orderedEnumSet.
    reasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, reasons);
    approvals = List.copyOf(approvals);
  }

  public static SafetyLatch initial(SafetyPolicy policy) {
    return new SafetyLatch(
        policy.spacecraftId(),
        policy.version(),
        1,
        true,
        Set.of(Reason.OPERATOR_ENABLE_REQUIRED),
        List.of());
  }

  public SafetyLatch freeze(Set<Reason> detected, boolean newlyObserved) {
    if (detected.isEmpty()) return this;
    var combined = EnumSet.noneOf(Reason.class);
    combined.addAll(reasons);
    combined.addAll(detected);
    boolean invalidate = !frozen || (newlyObserved && !approvals.isEmpty());
    return new SafetyLatch(
        spacecraftId,
        policyVersion,
        invalidate ? Math.addExact(generation, 1) : generation,
        true,
        combined,
        invalidate ? List.of() : approvals);
  }

  public SafetyLatch approve(
      SafetyPolicy policy, String actor, String decision, MissionInstant now) {
    if (!policy.spacecraftId().equals(spacecraftId) || policy.version() != policyVersion)
      throw new IllegalArgumentException("Safety policy binding mismatch");
    if (!frozen) return this;
    var current = new ArrayList<Approval>();
    approvals.stream()
        .filter(
            a ->
                a.generation() == generation
                    && a.grantedAt().compareTo(now) <= 0
                    && now.compareTo(a.validUntil()) < 0
                    && !a.actor().equals(actor))
        .forEach(current::add);
    current.add(
        new Approval(
            actor,
            decision,
            now,
            now.plus(new MissionDuration(policy.approvalValiditySeconds() * 1_000_000_000L)),
            generation));
    boolean complete =
        current.stream().map(Approval::actor).distinct().count() >= policy.recoveryApprovals();
    return new SafetyLatch(
        spacecraftId, policyVersion, generation, !complete, complete ? Set.of() : reasons, current);
  }
}
