package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.spacecraftcontrol.CommandLoad;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.*;
import msc.domain.spacecraftcontrol.TransmissionRecord;
import msc.domain.time.MissionInstant;
import org.junit.jupiter.api.Test;

class CommandReleaseGateTest {
  final CommandReleaseGate gate = new CommandReleaseGate();
  final CommandLoad load =
      new CommandCompiler(new CommandCompilerTest().json)
          .compile(new CommandLoadId("gate"), CommandCompilerTest.fixture(true))
          .load();
  final Binding binding = Binding.of(load);
  final List<String> steps = new ArrayList<>();

  CommandReleaseGate.Outcome release(Binding checked, Set<Reason> reasons, boolean lost) {
    return gate.release(
        load,
        checked,
        new Decision(reasons, "evidence"),
        () -> steps.add("intent"),
        authorized -> {
          steps.add("transmit");
          if (lost) throw new CommandReleaseGate.TransmissionUnconfirmed("LOST", null);
          return new TransmissionRecord(authorized.id(), MissionInstant.tai(1), "pass", "receipt");
        });
  }

  @Test
  void allowedDecisionRecordsIntentBeforeTransmit() {
    assertInstanceOf(CommandReleaseGate.Transmitted.class, release(binding, Set.of(), false));
    assertEquals(List.of("intent", "transmit"), steps);
  }

  @Test
  void refusedDecisionOrOtherLoadNeverReachesTheLink() {
    var refused = release(binding, Set.of(Reason.SAFETY_FROZEN), false);
    assertEquals(
        Set.of(Reason.SAFETY_FROZEN), ((CommandReleaseGate.Refused) refused).decision().reasons());
    var other =
        new Binding(
            new CommandLoadId("other"),
            binding.schedule(),
            binding.scheduleVersion(),
            binding.checksum(),
            binding.missionDefinitionVersion(),
            binding.correlationId());
    var mismatch = release(other, Set.of(), false);
    assertEquals(
        Set.of(Reason.CONTEXT_MISMATCH),
        ((CommandReleaseGate.Refused) mismatch).decision().reasons());
    assertEquals(List.of(), steps);
  }

  @Test
  void failedIntentWritePreventsTransmit() {
    assertThrows(
        IllegalStateException.class,
        () ->
            gate.release(
                load,
                binding,
                new Decision(Set.of(), "evidence"),
                () -> {
                  throw new IllegalStateException("db down");
                },
                authorized -> fail("transmitted without durable intent")));
  }

  @Test
  void uncertainTransferIsUnconfirmedAfterIntent() {
    var outcome = release(binding, Set.of(), true);
    assertEquals("LOST", ((CommandReleaseGate.Unconfirmed) outcome).detail());
    assertEquals(List.of("intent", "transmit"), steps);
  }
}
