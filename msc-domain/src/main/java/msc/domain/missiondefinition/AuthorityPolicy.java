package msc.domain.missiondefinition;

import msc.domain.anomaly.MissionPhase;

/** Interaction preference intentionally is not an input. */
public interface AuthorityPolicy {
  enum Requirement {
    AUTO_ALLOWED,
    POLICY_APPROVAL,
    HUMAN_APPROVAL,
    TWO_PERSON_APPROVAL,
    AUTO_FORBIDDEN
  }

  enum RiskClass {
    LOW,
    ELEVATED,
    CRITICAL
  }

  Requirement evaluate(
      String actionClass, MissionPhase phase, String spacecraftMode, RiskClass risk);
}
