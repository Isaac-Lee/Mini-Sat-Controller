package msc.domain.missiondefinition;

import static msc.domain.shared.Checks.*;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import msc.domain.anomaly.MissionPhase;
import msc.domain.shared.Ids.*;

/** Approved catalog entry. The template is a semantic reference, never a packet. */
public record ActivityDefinition(
    ActivityDefinitionId id,
    long version,
    String name,
    boolean approved,
    Set<ResourceId> exclusiveResources,
    Set<MissionPhase> allowedPhases,
    Set<String> allowedModes,
    AuthorityPolicy.RiskClass riskClass,
    String commandTemplateReference) {
  public ActivityDefinition {
    Objects.requireNonNull(id);
    positive(version);
    text(name);
    // Canonical, JVM-stable Set order: these fields serialize to JSON arrays that feed
    // fingerprints and public catalog snapshots. See Checks.orderedSet/orderedEnumSet.
    exclusiveResources = orderedSet(exclusiveResources, Comparator.comparing(ResourceId::value));
    allowedPhases = orderedEnumSet(MissionPhase.class, allowedPhases);
    allowedModes = orderedSet(allowedModes);
    Objects.requireNonNull(riskClass);
    text(commandTemplateReference);
  }

  public void requireSchedulable(MissionPhase phase, String mode) {
    if (!approved || !allowedPhases.contains(phase) || !allowedModes.contains(mode))
      throw new IllegalArgumentException("Activity not approved for phase/mode");
  }
}
