package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.time.MissionInstant;

/** Ground-delivered evidence of declared simulation effects, never hardware telemetry. */
public final class SimulationExecutionContracts {
  private SimulationExecutionContracts() {}

  public enum ModeledOutcome {
    EFFECT_APPLIED,
    REJECTED,
    NOT_SUPPORTED
  }

  public record CommandEvidence(
      String commandId, ModeledOutcome outcome, long completionTick, String catalogSha256) {
    public CommandEvidence {
      text(commandId);
      Objects.requireNonNull(outcome);
      if (completionTick < 0) throw new IllegalArgumentException("Negative completion tick");
      hash(catalogSha256);
    }
  }

  public record Observation(
      UUID scenarioId,
      String spacecraftId,
      String loadId,
      String environment,
      long ledgerVersion,
      String ledgerSha256,
      List<CommandEvidence> commands,
      MissionInstant simulatedObservedAt,
      MissionInstant simulatedReceivedAt) {
    public Observation {
      Objects.requireNonNull(scenarioId);
      text(spacecraftId);
      text(loadId);
      if (!"SIMULATION".equals(environment) || ledgerVersion <= 0)
        throw new IllegalArgumentException("Versioned SIMULATION evidence required");
      hash(ledgerSha256);
      commands = List.copyOf(commands);
      if (commands.isEmpty()
          || commands.size() > 100
          || commands.stream().map(CommandEvidence::commandId).distinct().count()
              != commands.size())
        throw new IllegalArgumentException("Unique bounded command evidence required");
      Objects.requireNonNull(simulatedObservedAt).requireTai();
      Objects.requireNonNull(simulatedReceivedAt).requireTai();
      if (simulatedReceivedAt.compareTo(simulatedObservedAt) < 0)
        throw new IllegalArgumentException("Reception precedes observation");
    }
  }

  private static void hash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("SHA-256 required");
  }
}
