package msc.platform;

import java.util.List;
import java.util.Optional;
import msc.domain.planning.*;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.TimeWindow;
import msc.ports.ScheduleRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicitly instantiated by the planning owner; never an automatically shared service bean. */
public final class JdbcScheduleRepository implements ScheduleRepository {
  private final StateStore store;
  private final Json json;
  private final JdbcTemplate db;

  public JdbcScheduleRepository(StateStore store, Json json, JdbcTemplate db) {
    this.store = store;
    this.json = json;
    this.db = db;
  }

  private String id(ScheduleKey key) {
    return json.fingerprint(key);
  }

  private MissionSchedule restore(StateStore.State<MissionSchedule.Snapshot> state) {
    if (state.version() != state.body().version())
      throw new IllegalStateException("Schedule head/history version mismatch");
    return MissionSchedule.restore(state.body());
  }

  public Optional<MissionSchedule> latest(ScheduleKey key) {
    return store
        .find("mission-schedule", id(key), MissionSchedule.Snapshot.class)
        .map(this::restore);
  }

  public Optional<MissionSchedule> version(ScheduleKey key, long version) {
    return store
        .version("mission-schedule", id(key), version, MissionSchedule.Snapshot.class)
        .map(this::restore);
  }

  public void commit(long expectedVersion, MissionSchedule next) {
    store.transaction(
        () -> {
          String id = id(next.key());
          lockSpacecraft(next.key().spacecraftId());
          store.lock("mission-schedule:" + id);
          var previous = latest(next.key()).orElseGet(() -> MissionSchedule.empty(next.key()));
          if (previous.version() != expectedVersion
              || next.version() != Math.addExact(expectedVersion, 1)) throw new VersionConflict();
          next.requireSuccessorOf(previous);
          for (var other : overlapping(next.key().spacecraftId(), next.key().horizon())) {
            if (!other.key().equals(next.key()))
              next.requireCrossHorizonSuccessorOf(previous, other);
          }
          if (expectedVersion == 0) store.create("mission-schedule", id, next.snapshot());
          else store.update("mission-schedule", id, expectedVersion, next.snapshot());
          return null;
        });
  }

  /** Hold through reading schedules, evaluating resources and publishing a successor. */
  public void lockSpacecraft(SpacecraftId spacecraftId) {
    store.lock("planning-spacecraft:" + spacecraftId.value());
  }

  public List<MissionSchedule> overlapping(SpacecraftId spacecraftId, TimeWindow horizon) {
    return db.query(
        """
        SELECT id,version,body::text FROM state_head
        WHERE kind='mission-schedule' AND body#>>'{key,spacecraftId,value}'=?
          AND ((body#>>'{key,horizon,start,seconds}')::bigint,
               (body#>>'{key,horizon,start,nanos}')::integer) < (?,?)
          AND ((body#>>'{key,horizon,end,seconds}')::bigint,
               (body#>>'{key,horizon,end,nanos}')::integer) > (?,?)
        ORDER BY (body#>>'{key,horizon,start,seconds}')::bigint,
                 (body#>>'{key,horizon,start,nanos}')::integer,id
        """,
        (rs, n) ->
            restore(
                new StateStore.State<>(
                    rs.getString(1),
                    rs.getLong(2),
                    json.read(rs.getString(3), MissionSchedule.Snapshot.class))),
        spacecraftId.value(),
        horizon.end().seconds(),
        horizon.end().nanos(),
        horizon.start().seconds(),
        horizon.start().nanos());
  }

  /** Resource forecasts must include later commitments, even after a proposal's own end. */
  public List<MissionSchedule> future(
      SpacecraftId spacecraftId, msc.domain.time.MissionInstant start) {
    start.requireTai();
    return db.query(
        """
        SELECT id,version,body::text FROM state_head
        WHERE kind='mission-schedule' AND body#>>'{key,spacecraftId,value}'=?
          AND ((body#>>'{key,horizon,end,seconds}')::bigint,
               (body#>>'{key,horizon,end,nanos}')::integer) > (?,?)
        ORDER BY (body#>>'{key,horizon,start,seconds}')::bigint,
                 (body#>>'{key,horizon,start,nanos}')::integer,id
        """,
        (rs, n) ->
            restore(
                new StateStore.State<>(
                    rs.getString(1),
                    rs.getLong(2),
                    json.read(rs.getString(3), MissionSchedule.Snapshot.class))),
        spacecraftId.value(),
        start.seconds(),
        start.nanos());
  }
}
