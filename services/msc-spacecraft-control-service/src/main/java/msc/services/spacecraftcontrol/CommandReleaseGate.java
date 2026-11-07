package msc.services.spacecraftcontrol;

import java.util.*;
import msc.domain.spacecraftcontrol.CommandLoad;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Binding;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Decision;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason;
import msc.domain.spacecraftcontrol.TransmissionRecord;
import msc.ports.SpacecraftLinkPort;

/**
 * The only intended caller of {@link SpacecraftLinkPort} (by convention; not enforced by a test).
 * A refused {@link Decision} never reaches the link. An allowed one records durable intent before
 * any outbound I/O. Transport uncertainty ends as UNCONFIRMED, which the caller must reconcile
 * from the receiving side before another attempt; adapter validation failures propagate after the
 * intent is committed, leaving the attempt record for reconciliation. The gate owns ordering, not
 * storage or owner reads: the caller must derive the Decision from current owner checks or
 * {@link msc.domain.spacecraftcontrol.CommandReleasePolicy}, since the gate cannot verify it.
 */
final class CommandReleaseGate {
  /** Thrown by a link adapter when the transfer may or may not have reached the receiver. */
  static final class TransmissionUnconfirmed extends RuntimeException {
    TransmissionUnconfirmed(String message, Throwable cause) {
      super(message, cause);
    }
  }

  sealed interface Outcome {}

  record Refused(Decision decision) implements Outcome {}

  record Transmitted(TransmissionRecord transmission) implements Outcome {}

  record Unconfirmed(String detail) implements Outcome {}

  /**
   * @param checked binding the owner checks were evaluated against; a different load is refused
   * @param recordIntent durable write committed before transmit; if it throws, nothing is sent
   */
  Outcome release(
      CommandLoad load,
      Binding checked,
      Decision decision,
      Runnable recordIntent,
      SpacecraftLinkPort link) {
    Objects.requireNonNull(load);
    Objects.requireNonNull(checked);
    Objects.requireNonNull(decision);
    Objects.requireNonNull(recordIntent);
    Objects.requireNonNull(link);
    var reasons = EnumSet.noneOf(Reason.class);
    reasons.addAll(decision.reasons());
    if (!Binding.of(load).equals(checked)) reasons.add(Reason.CONTEXT_MISMATCH);
    var gated = new Decision(reasons, decision.evidenceReference());
    if (!gated.allowed()) return new Refused(gated);
    recordIntent.run();
    try {
      return new Transmitted(link.transmit(load));
    } catch (TransmissionUnconfirmed unconfirmed) {
      return new Unconfirmed(unconfirmed.getMessage());
    }
  }
}
