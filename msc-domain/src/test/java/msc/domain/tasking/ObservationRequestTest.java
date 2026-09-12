package msc.domain.tasking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.ObservationRequest.*;
import msc.domain.time.MissionInstant;
import org.junit.jupiter.api.Test;

class ObservationRequestTest {
  private ObservationRequest received() {
    return new ObservationRequest(
        new RequestId("r"),
        Optional.empty(),
        1,
        "coverage:1",
        Optional.of(MissionInstant.tai(100)),
        0,
        InteractionPreference.AUTO,
        Status.RECEIVED);
  }

  @Test
  void unresolvedIntentCannotBecomeAcceptedWithoutArea() {
    var r = received();
    assertThrows(IllegalStateException.class, r::accept);
    var clarified = r.clarificationNeeded().accept(new AoiId("area-v1"));
    assertEquals(Status.ACCEPTED, clarified.status());
    assertEquals(Optional.of(new AoiId("area-v1")), clarified.resolvedAoi());
    assertTrue(r.resolvedAoi().isEmpty());
  }

  @Test
  void cancellationInvalidatesOldRevisionAndIsTerminal() {
    var r = received().accept(new AoiId("a")).scheduled().cancel();
    assertEquals(2, r.revision());
    assertTrue(r.terminal());
    assertThrows(IllegalStateException.class, r::scheduled);
    assertThrows(IllegalStateException.class, r::cancel);
  }

  @Test
  void deadlineIsExclusiveAndExpiryInvalidatesWork() {
    assertThrows(IllegalStateException.class, () -> received().expire(MissionInstant.tai(99)));
    assertEquals(Status.EXPIRED, received().expire(MissionInstant.tai(100)).status());
    assertEquals(2, received().expire(MissionInstant.tai(100)).revision());
  }

  @Test
  void partialFulfillmentRemainsPartialWhenAnotherScheduleArrives() {
    var r = received().accept(new AoiId("a")).fulfilled(false);
    assertEquals(Status.PARTIALLY_FULFILLED, r.scheduled().status());
    assertTrue(r.fulfilled(true).terminal());
    assertThrows(IllegalStateException.class, () -> r.fulfilled(true).cancel());
  }
}
