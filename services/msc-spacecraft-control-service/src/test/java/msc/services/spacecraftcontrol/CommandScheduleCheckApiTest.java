package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import msc.domain.planning.MissionSchedule;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class CommandScheduleCheckApiTest {
  final StateStore store = mock(StateStore.class);
  final ServiceHttp http = mock(ServiceHttp.class);
  final msc.ports.Clock clock = mock(msc.ports.Clock.class);
  final Json json = new Json(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  final CommandCompiler.Prepared prepared = new CommandCompiler(json).compile(new CommandLoadId("load"), CommandCompilerTest.fixture());
  final CommandScheduleCheckApi api = new CommandScheduleCheckApi(store, http, clock);

  void setup() {
    when(store.require("prepared-command-load", "load", CommandCompiler.Prepared.class))
        .thenReturn(new StateStore.State<>("load", 1, prepared));
    when(clock.now()).thenReturn(MissionInstant.tai(1000));
  }

  @Test
  void checksOwnerOnEveryCallAndDetectsScheduleAdvancement() {
    setup();
    var old = prepared.sources().schedule();
    var next = new MissionSchedule.Snapshot(old.key(), old.version() + 1, old.frozenUntil(),
        old.activities(), old.assignments(), old.resourceValidation());
    when(http.post(eq("planning"), eq("/internal/planning/schedules/current"), eq(old.key()), anyString(), eq(MissionSchedule.Snapshot.class)))
        .thenReturn(old, next);
    assertTrue(api.check("load").reasons().isEmpty());
    assertEquals(Set.of(CommandScheduleCheckApi.Reason.STALE_SCHEDULE), api.check("load").reasons());
    verify(http, times(2)).post(eq("planning"), anyString(), eq(old.key()), anyString(), eq(MissionSchedule.Snapshot.class));
    verify(store, never()).create(anyString(), anyString(), any());
  }

  @Test
  void missingScheduleIsExplicitAndDeadlineIsCheckedAfterRemoteCall() {
    setup();
    when(http.post(eq("planning"), anyString(), any(), anyString(), eq(MissionSchedule.Snapshot.class)))
        .thenAnswer(invocation -> {
          when(clock.now()).thenReturn(prepared.load().commitDeadline());
          throw org.springframework.web.client.HttpClientErrorException.create(
              org.springframework.http.HttpStatus.NOT_FOUND, "missing", null, new byte[0], null);
        });
    var result = api.check("load");
    assertTrue(result.currentSchedule().isEmpty());
    assertEquals(Set.of(CommandScheduleCheckApi.Reason.SCHEDULE_MISSING,
        CommandScheduleCheckApi.Reason.DEADLINE_PASSED), result.reasons());
  }

  @Test
  void sameVersionDifferentContentCannotPass() {
    setup();
    var old = prepared.sources().schedule();
    var changed = new MissionSchedule.Snapshot(old.key(), old.version(),
        MissionInstant.tai(1001), old.activities(), old.assignments(), old.resourceValidation());
    when(http.post(eq("planning"), anyString(), any(), anyString(), eq(MissionSchedule.Snapshot.class)))
        .thenReturn(changed);
    assertEquals(Set.of(CommandScheduleCheckApi.Reason.SCHEDULE_CONTENT_MISMATCH), api.check("load").reasons());
  }
}
