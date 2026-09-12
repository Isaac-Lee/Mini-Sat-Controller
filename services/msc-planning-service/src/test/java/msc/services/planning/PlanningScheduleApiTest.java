package msc.services.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import msc.domain.planning.*;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PlanningScheduleApiTest {
  final Json json =
      new Json(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  final StateStore store = mock(StateStore.class);
  final PlanningScheduleApi api = new PlanningScheduleApi(store, json, mock(JdbcTemplate.class));
  final ScheduleKey key =
      new ScheduleKey(
          new SpacecraftId("synthetic"),
          new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(1100)));

  @Test
  void readsRequestedHistoryRatherThanCurrentHead() {
    var snapshot =
        new MissionSchedule.Snapshot(
            key,
            3,
            MissionInstant.tai(1000),
            List.of(),
            List.of(),
            ResourceValidation.notEvaluated());
    when(store.version(
            "mission-schedule", json.fingerprint(key), 3, MissionSchedule.Snapshot.class))
        .thenReturn(Optional.of(new StateStore.State<>(json.fingerprint(key), 3, snapshot)));
    assertEquals(snapshot, api.version(new PlanningScheduleApi.Query(key, 3)));
    verify(store)
        .version("mission-schedule", json.fingerprint(key), 3, MissionSchedule.Snapshot.class);
    verifyNoMoreInteractions(store);
  }

  @Test
  void missingVersionDoesNotInventAnEmptySchedule() {
    when(store.version(
            "mission-schedule", json.fingerprint(key), 3, MissionSchedule.Snapshot.class))
        .thenReturn(Optional.empty());
    assertThrows(ApiException.class, () -> api.version(new PlanningScheduleApi.Query(key, 3)));
    assertThrows(IllegalArgumentException.class, () -> new PlanningScheduleApi.Query(key, 0));
  }
}
