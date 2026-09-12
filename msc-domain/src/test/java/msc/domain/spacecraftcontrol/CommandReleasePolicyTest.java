package msc.domain.spacecraftcontrol;

import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import msc.domain.planning.*;
import msc.domain.missiondefinition.AuthorityPolicy;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandReleasePolicyTest {
  final CommandReleasePolicy policy=new CommandReleasePolicy();
  final TimeCorrelationId correlation=new TimeCorrelationId("correlation-v1");
  final CommandLoad load=new CommandLoad(new CommandLoadId("load"),
      new ScheduleKey(new SpacecraftId("sat"),new TimeWindow(MissionInstant.tai(0),MissionInstant.tai(100))),1,
      List.of(new CommandInstance(new CommandId("cmd"),"imaging-v1",Map.of(),new OnboardTime(30,"boot-1",correlation))),
      "mission-v1",correlation,"checksum",Optional.empty(),MissionInstant.tai(20));
  Context context(Safety safety,ResourceValidation.Status resources,Optional<AuthorityPolicy.Requirement> authority,List<Approval> approvals) {
    return new Context(Binding.of(load),1,resources,true,safety,authority,approvals,MissionInstant.tai(0),MissionInstant.tai(19),"evidence-v1");
  }
  Context ready() { return context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,Optional.of(AuthorityPolicy.Requirement.AUTO_ALLOWED),List.of()); }
  Approval approval(String actor) { return new Approval(Binding.of(load),ApprovalKind.HUMAN,actor,MissionInstant.tai(0),MissionInstant.tai(18),false); }
  @Test void permitsOnlyCompletePositiveEvidence() { assertTrue(policy.evaluate(load,ready(),MissionInstant.tai(10)).allowed()); }
  @Test void mixedRequirementsCannotDowngradePolicyOrHumanApproval() {
    var required=Set.of(AuthorityPolicy.Requirement.AUTO_ALLOWED,
        AuthorityPolicy.Requirement.POLICY_APPROVAL, AuthorityPolicy.Requirement.TWO_PERSON_APPROVAL);
    var binding=Binding.of(load); var now=MissionInstant.tai(10);
    var human=List.of(approval("a"),approval("b"));
    assertEquals(Set.of(Reason.APPROVAL_MISSING), CommandReleasePolicy.approvalReasons(binding,required,human,now));
    var policyApproval=new Approval(binding,ApprovalKind.POLICY,"policy-engine",MissionInstant.tai(0),MissionInstant.tai(18),false);
    assertEquals(Set.of(Reason.APPROVAL_MISSING),CommandReleasePolicy.approvalReasons(binding,required,List.of(policyApproval),now));
    assertTrue(CommandReleasePolicy.approvalReasons(binding,required,List.of(policyApproval,approval("a"),approval("b")),now).isEmpty());
    assertEquals(Set.of(Reason.AUTHORITY_UNKNOWN),CommandReleasePolicy.approvalReasons(binding,Set.of(),human,now));
    assertEquals(Set.of(Reason.AUTO_FORBIDDEN),CommandReleasePolicy.approvalReasons(binding,
        Set.of(AuthorityPolicy.Requirement.AUTO_ALLOWED,AuthorityPolicy.Requirement.AUTO_FORBIDDEN),human,now));
  }
  @Test void unknownOrFrozenSafetyBlocksRegardlessOfAutoAuthority() {
    for(var safety:List.of(Safety.UNKNOWN,Safety.FROZEN)) assertFalse(policy.evaluate(load,context(safety,ResourceValidation.Status.VALIDATED,Optional.of(AuthorityPolicy.Requirement.AUTO_ALLOWED),List.of()),MissionInstant.tai(10)).allowed());
  }
  @Test void unvalidatedAndRejectedResourcesBlock() {
    for(var resource:List.of(ResourceValidation.Status.NOT_EVALUATED,ResourceValidation.Status.REJECTED)) assertTrue(policy.evaluate(load,context(Safety.CLEAR,resource,Optional.of(AuthorityPolicy.Requirement.AUTO_ALLOWED),List.of()),MissionInstant.tai(10)).reasons().contains(Reason.RESOURCE_UNVALIDATED));
  }
  @Test void unknownAndForbiddenAuthorityBlock() {
    assertTrue(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,Optional.empty(),List.of()),MissionInstant.tai(10)).reasons().contains(Reason.AUTHORITY_UNKNOWN));
    assertTrue(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,Optional.of(AuthorityPolicy.Requirement.AUTO_FORBIDDEN),List.of(approval("a"),approval("b"))),MissionInstant.tai(10)).reasons().contains(Reason.AUTO_FORBIDDEN));
  }
  @Test void twoPersonApprovalRequiresDistinctActors() {
    var authority=Optional.of(AuthorityPolicy.Requirement.TWO_PERSON_APPROVAL);
    assertFalse(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,authority,List.of(approval("a"),approval("a"))),MissionInstant.tai(10)).allowed());
    assertTrue(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,authority,List.of(approval("a"),approval("b"))),MissionInstant.tai(10)).allowed());
  }
  @Test void expiredRevokedOrDifferentlyBoundApprovalsDoNotCount() {
    var original=approval("a");
    var other=new Binding(new CommandLoadId("other"),load.scheduleKey(),1,load.checksum(),load.missionDefinitionVersion(),correlation);
    for(var approval:List.of(
        new Approval(original.binding(),ApprovalKind.HUMAN,"a",MissionInstant.tai(0),MissionInstant.tai(10),false),
        new Approval(original.binding(),ApprovalKind.HUMAN,"a",MissionInstant.tai(0),MissionInstant.tai(18),true),
        new Approval(other,ApprovalKind.HUMAN,"a",MissionInstant.tai(0),MissionInstant.tai(18),false))) {
      assertFalse(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,Optional.of(AuthorityPolicy.Requirement.HUMAN_APPROVAL),List.of(approval)),MissionInstant.tai(10)).allowed());
    }
  }
  @Test void policyApprovalCannotBeReplacedByHumanApproval() {
    assertFalse(policy.evaluate(load,context(Safety.CLEAR,ResourceValidation.Status.VALIDATED,Optional.of(AuthorityPolicy.Requirement.POLICY_APPROVAL),List.of(approval("a"))),MissionInstant.tai(10)).allowed());
  }
  @Test void staleScheduleAndMissingBookingBlock() {
    var c=ready();
    var wrong=new Context(c.binding(),2,c.resourceStatus(),false,c.safety(),c.authority(),c.approvals(),c.capturedAt(),c.validUntil(),c.evidenceReference());
    var result=policy.evaluate(load,wrong,MissionInstant.tai(10));
    assertTrue(result.reasons().containsAll(Set.of(Reason.STALE_SCHEDULE,Reason.BOOKING_UNCONFIRMED)));
  }
  @Test void contextAndDeadlineHaveExclusiveExpiry() {
    assertTrue(policy.evaluate(load,ready(),MissionInstant.tai(19)).reasons().contains(Reason.STALE_CONTEXT));
    assertTrue(policy.evaluate(load,ready(),MissionInstant.tai(20)).reasons().contains(Reason.DEADLINE_PASSED));
    assertTrue(policy.evaluate(load,ready(),MissionInstant.tai(-1)).reasons().contains(Reason.STALE_CONTEXT));
  }
}
