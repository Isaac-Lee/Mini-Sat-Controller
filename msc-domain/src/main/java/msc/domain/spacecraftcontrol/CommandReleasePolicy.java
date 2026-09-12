package msc.domain.spacecraftcontrol;

import java.util.*;
import msc.domain.planning.ScheduleKey;
import msc.domain.planning.ResourceValidation;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;
import msc.domain.missiondefinition.AuthorityPolicy;
import static msc.domain.shared.Checks.*;

/** Pure, fail-closed release predicates. Context and approvals must come from their trusted owners. */
public final class CommandReleasePolicy {
  public enum Safety { CLEAR, FROZEN, UNKNOWN }
  public enum ApprovalKind { POLICY, HUMAN }
  public enum Reason {
    CONTEXT_MISMATCH, STALE_CONTEXT, STALE_SCHEDULE, DEADLINE_PASSED,
    RESOURCE_UNVALIDATED, BOOKING_UNCONFIRMED, SAFETY_FROZEN, SAFETY_UNKNOWN,
    AUTHORITY_UNKNOWN, AUTO_FORBIDDEN, APPROVAL_MISSING
  }
  public record Binding(CommandLoadId loadId, ScheduleKey schedule, long scheduleVersion,
      String checksum, String missionDefinitionVersion, TimeCorrelationId correlationId) {
    public Binding { Objects.requireNonNull(loadId); Objects.requireNonNull(schedule); positive(scheduleVersion); text(checksum); text(missionDefinitionVersion); Objects.requireNonNull(correlationId); }
    public static Binding of(CommandLoad load) {
      return new Binding(load.id(),load.scheduleKey(),load.scheduleVersion(),load.checksum(),load.missionDefinitionVersion(),load.timeCorrelationId());
    }
  }
  public record Approval(Binding binding, ApprovalKind kind, String actorId,
      MissionInstant grantedAt, MissionInstant validUntil, boolean revoked) {
    public Approval {
      Objects.requireNonNull(binding); Objects.requireNonNull(kind); text(actorId);
      Objects.requireNonNull(grantedAt).requireTai(); Objects.requireNonNull(validUntil).requireTai();
      if(grantedAt.compareTo(validUntil)>=0) throw new IllegalArgumentException("Empty approval validity");
    }
    boolean applies(Binding expected,MissionInstant now) {
      return binding.equals(expected)&&!revoked&&grantedAt.compareTo(now)<=0&&now.compareTo(validUntil)<0;
    }
  }
  public record Context(Binding binding,long currentScheduleVersion,
      ResourceValidation.Status resourceStatus, boolean externalBookingsConfirmed,
      Safety safety, Optional<AuthorityPolicy.Requirement> authority,
      List<Approval> approvals, MissionInstant capturedAt,MissionInstant validUntil,String evidenceReference) {
    public Context {
      Objects.requireNonNull(binding); positive(currentScheduleVersion); Objects.requireNonNull(resourceStatus);
      Objects.requireNonNull(safety); Objects.requireNonNull(authority); approvals=List.copyOf(approvals);
      Objects.requireNonNull(capturedAt).requireTai(); Objects.requireNonNull(validUntil).requireTai(); text(evidenceReference);
      if(capturedAt.compareTo(validUntil)>=0) throw new IllegalArgumentException("Empty context validity");
    }
  }
  public record Decision(Set<Reason> reasons,String evidenceReference) {
    // Canonical, JVM-stable Set order: see Checks.orderedEnumSet.
    public Decision { reasons=orderedEnumSet(Reason.class, reasons); text(evidenceReference); }
    public boolean allowed() { return reasons.isEmpty(); }
  }
  public Decision evaluate(CommandLoad load,Context context,MissionInstant now) {
    Objects.requireNonNull(load); Objects.requireNonNull(context); Objects.requireNonNull(now).requireTai();
    var reasons=EnumSet.noneOf(Reason.class); var binding=Binding.of(load);
    if(!binding.equals(context.binding())) reasons.add(Reason.CONTEXT_MISMATCH);
    if(now.compareTo(context.capturedAt())<0||now.compareTo(context.validUntil())>=0) reasons.add(Reason.STALE_CONTEXT);
    if(load.scheduleVersion()!=context.currentScheduleVersion()) reasons.add(Reason.STALE_SCHEDULE);
    if(now.compareTo(load.commitDeadline())>=0) reasons.add(Reason.DEADLINE_PASSED);
    if(context.resourceStatus()!=ResourceValidation.Status.VALIDATED) reasons.add(Reason.RESOURCE_UNVALIDATED);
    if(!context.externalBookingsConfirmed()) reasons.add(Reason.BOOKING_UNCONFIRMED);
    if(context.safety()==Safety.FROZEN) reasons.add(Reason.SAFETY_FROZEN);
    if(context.safety()==Safety.UNKNOWN) reasons.add(Reason.SAFETY_UNKNOWN);
    if(context.authority().isEmpty()) reasons.add(Reason.AUTHORITY_UNKNOWN);
    else {
      var approvals=context.approvals().stream().filter(a -> a.applies(binding,now)).toList();
      long humans=approvals.stream().filter(a -> a.kind()==ApprovalKind.HUMAN).map(Approval::actorId).distinct().count();
      switch(context.authority().get()) {
        case AUTO_ALLOWED -> { }
        case AUTO_FORBIDDEN -> reasons.add(Reason.AUTO_FORBIDDEN);
        case POLICY_APPROVAL -> { if(approvals.stream().noneMatch(a -> a.kind()==ApprovalKind.POLICY)) reasons.add(Reason.APPROVAL_MISSING); }
        case HUMAN_APPROVAL -> { if(humans<1) reasons.add(Reason.APPROVAL_MISSING); }
        case TWO_PERSON_APPROVAL -> { if(humans<2) reasons.add(Reason.APPROVAL_MISSING); }
      }
    }
    return new Decision(reasons,context.evidenceReference());
  }
}
