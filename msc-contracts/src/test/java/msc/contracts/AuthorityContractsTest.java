package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import msc.contracts.AuthorityContracts.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.AuthorityPolicy.*;
import org.junit.jupiter.api.Test;

class AuthorityContractsTest {
  @Test
  void everyContextDimensionMustMatchAndMissingRulesForbidAutomaticRelease() {
    var context = new Context("IMAGE", MissionPhase.ROUTINE, "NOMINAL", RiskClass.LOW);
    var policy = new Policy("sat", "v1", List.of(new Rule(context, Requirement.TWO_PERSON_APPROVAL)), "operator rule");
    assertEquals(Requirement.TWO_PERSON_APPROVAL, policy.evaluate("IMAGE", MissionPhase.ROUTINE, "NOMINAL", RiskClass.LOW));
    assertEquals(Requirement.AUTO_FORBIDDEN, policy.evaluate("DOWNLINK", MissionPhase.ROUTINE, "NOMINAL", RiskClass.LOW));
    assertEquals(Requirement.AUTO_FORBIDDEN, policy.evaluate("IMAGE", MissionPhase.CONTINGENCY, "NOMINAL", RiskClass.LOW));
    assertEquals(Requirement.AUTO_FORBIDDEN, policy.evaluate("IMAGE", MissionPhase.ROUTINE, "SAFE", RiskClass.LOW));
    assertEquals(Requirement.AUTO_FORBIDDEN, policy.evaluate("IMAGE", MissionPhase.ROUTINE, "NOMINAL", RiskClass.CRITICAL));
    assertEquals(Requirement.AUTO_FORBIDDEN, new Policy("sat", "v1", List.of(), "deny all")
        .evaluate("IMAGE", MissionPhase.ROUTINE, "NOMINAL", RiskClass.LOW));
  }

  @Test
  void duplicateContextsCannotMakeRuleOrderDecideAuthority() {
    var context = new Context("IMAGE", MissionPhase.ROUTINE, "NOMINAL", RiskClass.LOW);
    assertThrows(IllegalArgumentException.class, () -> new Policy("sat", "v1", List.of(
        new Rule(context, Requirement.AUTO_ALLOWED), new Rule(context, Requirement.AUTO_FORBIDDEN)), "ambiguous"));
  }
}
