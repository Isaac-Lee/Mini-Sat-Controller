package msc.contracts;

import static msc.domain.shared.Checks.*;

import java.util.*;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy;

/** Version-1 published mission catalog contracts; no server or persistence dependencies. */
public final class CatalogContracts {
  private CatalogContracts() {}

  public enum ParameterType {
    NUMBER,
    TEXT,
    BOOLEAN
  }

  public record ParameterRule(
      ParameterType type,
      boolean required,
      double minimum,
      double maximum,
      Set<String> allowedValues) {
    public ParameterRule {
      Objects.requireNonNull(type);
      // Canonical, JVM-stable Set order: see Checks.orderedSet.
      allowedValues = orderedSet(allowedValues);
      if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum)
        throw new IllegalArgumentException("Invalid parameter range");
    }

    public void validate(String value) {
      if (value == null) {
        if (required) throw new IllegalArgumentException("Required parameter missing");
        return;
      }
      if (value.length() > 1024) throw new IllegalArgumentException("Parameter too long");
      switch (type) {
        case NUMBER -> {
          double number = Double.parseDouble(value);
          if (!Double.isFinite(number) || number < minimum || number > maximum)
            throw new IllegalArgumentException("Number outside approved range");
        }
        case BOOLEAN -> {
          if (!Set.of("true", "false").contains(value))
            throw new IllegalArgumentException("Boolean required");
        }
        case TEXT -> {
          if (!allowedValues.isEmpty() && !allowedValues.contains(value))
            throw new IllegalArgumentException("Unapproved parameter value");
        }
      }
    }
  }

  public record CommandTemplate(
      String id, long version, String operation, Map<String, ParameterRule> parameters) {
    public CommandTemplate {
      text(id);
      positive(version);
      text(operation);
      parameters = Map.copyOf(parameters);
    }

    public void validate(Map<String, String> values) {
      if (!parameters.keySet().containsAll(values.keySet()))
        throw new IllegalArgumentException("Unknown command parameter");
      parameters.forEach((name, rule) -> rule.validate(values.get(name)));
    }
  }

  public record ResourceProfile(
      double powerWatts, double generatedMegabytes, double propellantKilograms) {
    public ResourceProfile {
      for (double value : new double[] {powerWatts, generatedMegabytes, propellantKilograms})
        if (!Double.isFinite(value) || value < 0)
          throw new IllegalArgumentException("Invalid resource profile");
    }
  }

  public record CatalogEntry(
      String id,
      long version,
      ActivityDefinition activity,
      CommandTemplate template,
      ResourceProfile resources,
      AuthorityPolicy.Requirement authority,
      double durationSeconds,
      String approvalReference) {
    public CatalogEntry {
      text(id);
      positive(version);
      Objects.requireNonNull(activity);
      Objects.requireNonNull(template);
      Objects.requireNonNull(resources);
      Objects.requireNonNull(authority);
      text(approvalReference);
      if (!activity.approved()
          || !activity.commandTemplateReference().equals(template.id() + ":" + template.version())
          || !Double.isFinite(durationSeconds)
          || durationSeconds <= 0
          || durationSeconds > 3600)
        throw new IllegalArgumentException(
            "Catalog entry is not an approved bounded activity/template");
    }
  }

  public record MissionProfile(
      String spacecraftId,
      String catalogId,
      long catalogVersion,
      String missionDefinitionVersion,
      double batteryCapacityWh,
      double minimumBatteryWh,
      double storageCapacityMb,
      double propellantKg,
      double rechargeWatts,
      String timeCorrelationId,
      String provenance) {
    public MissionProfile {
      text(spacecraftId);
      text(catalogId);
      positive(catalogVersion);
      text(missionDefinitionVersion);
      text(timeCorrelationId);
      text(provenance);
      for (double value :
          new double[] {
            batteryCapacityWh, minimumBatteryWh, storageCapacityMb, propellantKg, rechargeWatts
          })
        if (!Double.isFinite(value) || value < 0)
          throw new IllegalArgumentException("Invalid mission resource limit");
      if (batteryCapacityWh <= minimumBatteryWh || storageCapacityMb <= 0)
        throw new IllegalArgumentException("Invalid mission capacity");
    }
  }
}
