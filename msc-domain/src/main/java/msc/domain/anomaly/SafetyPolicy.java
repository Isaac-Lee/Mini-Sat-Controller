package msc.domain.anomaly;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.time.MissionInstant;

/** Configured safety evidence predicates, independent of command authority and transport. */
public record SafetyPolicy(
    String spacecraftId,
    long version,
    long telemetryBindingVersion,
    double minimumBatteryWh,
    double maximumStorageMb,
    double minimumPropellantKg,
    int recoveryApprovals,
    int approvalValiditySeconds,
    String approvalReference) {
  public enum Reason {
    OPERATOR_ENABLE_REQUIRED,
    BINDING_MISMATCH,
    MONITORING_UNAVAILABLE,
    UNKNOWN_STATE,
    STALE_STATE,
    DEGRADED_STATE,
    SAFE_MODE,
    LOW_BATTERY,
    STORAGE_LIMIT,
    LOW_PROPELLANT
  }

  public SafetyPolicy {
    text(spacecraftId);
    text(approvalReference);
    if (!spacecraftId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        || version < 1
        || telemetryBindingVersion < 1
        || (recoveryApprovals != 1 && recoveryApprovals != 2)
        || approvalValiditySeconds < 1
        || approvalValiditySeconds > 600)
      throw new IllegalArgumentException("Invalid safety policy");
    for (double value : new double[] {minimumBatteryWh, maximumStorageMb, minimumPropellantKg})
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Invalid safety resource threshold");
  }

  public Set<Reason> evaluate(Estimate estimate, MissionInstant now) {
    var reasons = EnumSet.noneOf(Reason.class);
    if (!spacecraftId.equals(estimate.binding().spacecraftId())
        || telemetryBindingVersion != estimate.binding().version())
      reasons.add(Reason.BINDING_MISMATCH);
    switch (estimate.confidence(now)) {
      case UNKNOWN -> reasons.add(Reason.UNKNOWN_STATE);
      case STALE -> reasons.add(Reason.STALE_STATE);
      case DEGRADED -> reasons.add(Reason.DEGRADED_STATE);
      case FRESH -> {}
    }
    estimate
        .accepted()
        .ifPresent(
            receipt -> {
              var frame = receipt.frame();
              if (frame.mode() == Mode.SAFE) reasons.add(Reason.SAFE_MODE);
              if (frame.batteryWh() < minimumBatteryWh) reasons.add(Reason.LOW_BATTERY);
              if (frame.storageMb() > maximumStorageMb) reasons.add(Reason.STORAGE_LIMIT);
              if (frame.propellantKg() < minimumPropellantKg) reasons.add(Reason.LOW_PROPELLANT);
            });
    return Set.copyOf(reasons);
  }
}
