package msc.domain.monitoring;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.time.*;

/** Explicitly sourced telemetry evidence and an estimate; neither is spacecraft truth. */
public final class OperationalTelemetry {
  private OperationalTelemetry() {}

  public enum Environment {
    SIMULATION,
    HARDWARE
  }

  public enum Mode {
    NOMINAL,
    SAFE,
    UNKNOWN
  }

  public enum Confidence {
    UNKNOWN,
    FRESH,
    DEGRADED,
    STALE
  }

  public enum Disposition {
    ACCEPTED,
    OUT_OF_ORDER,
    BAD_QUALITY,
    FUTURE_TIMESTAMP,
    SUPERSEDED_BINDING
  }

  public record Binding(
      String spacecraftId,
      long version,
      String source,
      Environment environment,
      int maximumAgeSeconds,
      int futureSkewSeconds,
      String approvalReference) {
    public Binding {
      text(spacecraftId);
      text(source);
      text(approvalReference);
      Objects.requireNonNull(environment);
      if (!spacecraftId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
          || source.length() > 160
          || version < 1
          || maximumAgeSeconds < 1
          || maximumAgeSeconds > 3600
          || futureSkewSeconds < 0
          || futureSkewSeconds > 30)
        throw new IllegalArgumentException("Invalid telemetry admission binding");
      if ((environment == Environment.SIMULATION) != source.startsWith("simulator:"))
        throw new IllegalArgumentException("Simulation source and environment must agree");
    }
  }

  public record Frame(
      UUID id,
      String spacecraftId,
      long bindingVersion,
      String source,
      long sequence,
      MissionInstant observedAt,
      TelemetryObservation.Quality quality,
      Mode mode,
      double batteryWh,
      double storageMb,
      double propellantKg,
      String provenance) {
    public Frame {
      Objects.requireNonNull(id);
      text(spacecraftId);
      text(source);
      text(provenance);
      Objects.requireNonNull(observedAt).requireTai();
      Objects.requireNonNull(quality);
      Objects.requireNonNull(mode);
      if (bindingVersion < 1 || sequence < 0 || provenance.length() > 1000 || source.length() > 160)
        throw new IllegalArgumentException("Invalid telemetry frame identity");
      for (double value : new double[] {batteryWh, storageMb, propellantKg})
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite telemetry");
    }

    boolean usable() {
      return quality == TelemetryObservation.Quality.GOOD
          && mode != Mode.UNKNOWN
          && batteryWh >= 0
          && storageMb >= 0
          && propellantKg >= 0;
    }
  }

  public record Receipt(Frame frame, MissionInstant receivedAt, Disposition disposition) {}

  public record Estimate(
      Binding binding, Optional<Receipt> accepted, Optional<Receipt> newestEvidence) {
    public Estimate {
      Objects.requireNonNull(binding);
      accepted = Objects.requireNonNull(accepted);
      newestEvidence = Objects.requireNonNull(newestEvidence);
    }

    public static Estimate empty(Binding binding) {
      return new Estimate(binding, Optional.empty(), Optional.empty());
    }

    public Transition observe(Frame frame, MissionInstant receivedAt) {
      receivedAt.requireTai();
      if (!binding.spacecraftId().equals(frame.spacecraftId())
          || !binding.source().equals(frame.source())
          || binding.version() != frame.bindingVersion())
        throw new IllegalArgumentException("Telemetry does not match active source binding");
      if (frame
              .observedAt()
              .compareTo(
                  receivedAt.plus(
                      new MissionDuration(binding.futureSkewSeconds() * 1_000_000_000L)))
          > 0)
        return new Transition(this, new Receipt(frame, receivedAt, Disposition.FUTURE_TIMESTAMP));
      boolean newer = newestEvidence.isEmpty() || compare(frame, newestEvidence.get().frame()) > 0;
      boolean improvesAccepted =
          frame.usable() && (accepted.isEmpty() || compare(frame, accepted.get().frame()) > 0);
      var receipt =
          new Receipt(
              frame,
              receivedAt,
              improvesAccepted
                  ? Disposition.ACCEPTED
                  : newer ? Disposition.BAD_QUALITY : Disposition.OUT_OF_ORDER);
      if (!newer && !improvesAccepted) return new Transition(this, receipt);
      return new Transition(
          new Estimate(
              binding,
              improvesAccepted ? Optional.of(receipt) : accepted,
              newer ? Optional.of(receipt) : newestEvidence),
          receipt);
    }

    public Confidence confidence(MissionInstant now) {
      now.requireTai();
      if (accepted.isEmpty()) return Confidence.UNKNOWN;
      var frame = accepted.get().frame();
      if (now.compareTo(
              frame
                  .observedAt()
                  .plus(new MissionDuration(binding.maximumAgeSeconds() * 1_000_000_000L)))
          >= 0) return Confidence.STALE;
      if (now.compareTo(frame.observedAt()) < 0
          || (newestEvidence.isPresent() && !newestEvidence.get().frame().id().equals(frame.id())))
        return Confidence.DEGRADED;
      return Confidence.FRESH;
    }
  }

  public record Transition(Estimate estimate, Receipt receipt) {}

  private static int compare(Frame a, Frame b) {
    int time = a.observedAt().compareTo(b.observedAt());
    return time != 0 ? time : Long.compare(a.sequence(), b.sequence());
  }
}
