package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.AuthorityPolicy;

/** Explicit owner-authored rules. Requester interaction preferences are never authority input. */
public final class AuthorityContracts {
  private AuthorityContracts() {}

  public record Context(String actionClass, MissionPhase phase, String spacecraftMode,
      AuthorityPolicy.RiskClass risk) {
    public Context {
      text(actionClass);
      Objects.requireNonNull(phase);
      text(spacecraftMode);
      Objects.requireNonNull(risk);
    }
  }

  public record Rule(Context context, AuthorityPolicy.Requirement requirement) {
    public Rule {
      Objects.requireNonNull(context);
      Objects.requireNonNull(requirement);
    }
  }

  public record Policy(String spacecraftId, String missionDefinitionVersion,
      List<Rule> rules, String provenance) implements AuthorityPolicy {
    public Policy {
      text(spacecraftId);
      text(missionDefinitionVersion);
      text(provenance);
      rules = List.copyOf(rules);
      if (rules.size() > 1000) throw new IllegalArgumentException("At most 1000 exact authority rules");
      var contexts = new HashSet<Context>();
      for (var rule : rules)
        if (!contexts.add(rule.context()))
          throw new IllegalArgumentException("Duplicate authority context");
    }

    @Override
    public Requirement evaluate(String actionClass, MissionPhase phase, String spacecraftMode, RiskClass risk) {
      var requested = new Context(actionClass, phase, spacecraftMode, risk);
      return rules.stream().filter(r -> r.context().equals(requested)).map(Rule::requirement)
          .findFirst().orElse(Requirement.AUTO_FORBIDDEN);
    }
  }

  public record Publish(long expectedVersion, Policy policy) {
    public Publish {
      if (expectedVersion < 0) throw new IllegalArgumentException("Nonnegative version required");
      Objects.requireNonNull(policy);
    }
  }
}
