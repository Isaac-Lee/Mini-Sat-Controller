package msc.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import msc.domain.time.MissionInstant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlatformPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Container
  static final RabbitMQContainer rabbit =
      new RabbitMQContainer("rabbitmq:4.1.4-management-alpine")
          .withStartupTimeout(java.time.Duration.ofSeconds(180));

  StateStore store;
  JdbcTemplate db;
  TransactionTemplate tx;
  Json json;
  CachingConnectionFactory connection;
  RabbitTemplate template;

  record Counter(int count) {}

  @BeforeEach
  void setup() {
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    db = new JdbcTemplate(data);
    db.execute("TRUNCATE state_head,state_history,outbox,inbox,idempotency");
    tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    json = new Json(new ObjectMapper().findAndRegisterModules());
    store = new StateStore(db, tx, json, () -> MissionInstant.tai(100));
    connection = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
    connection.setUsername(rabbit.getAdminUsername());
    connection.setPassword(rabbit.getAdminPassword());
    connection.setPublisherConfirmType(ConfirmType.CORRELATED);
    connection.setPublisherReturns(true);
    template = new RabbitTemplate(connection);
    template.setMandatory(true);
    var admin = new RabbitAdmin(connection);
    admin.declareExchange(new TopicExchange(EventTopology.EXCHANGE, true, false));
    admin.declareQueue(new org.springframework.amqp.core.Queue("platform-test", true));
    admin.declareBinding(
        new Binding(
            "platform-test", Binding.DestinationType.QUEUE, EventTopology.EXCHANGE, "#", null));
    admin.purgeQueue("platform-test", false);
  }

  @AfterEach
  void close() {
    connection.destroy();
  }

  @Test
  void simulationModelPublicationReachesPlanningQueue() {
    var admin = new RabbitAdmin(connection);
    for (var declaration : new EventTopology().eventQueues().getDeclarables()) {
      if (declaration instanceof Exchange exchange) admin.declareExchange(exchange);
      else if (declaration instanceof org.springframework.amqp.core.Queue queue)
        admin.declareQueue(queue);
      else if (declaration instanceof Binding binding) admin.declareBinding(binding);
    }
    for (var type :
        List.of(
            "SimulationPlanningModelPublished",
            "MissionCatalogBindingsPublished",
            "OperationResourceProfilesPublished")) {
      String publication = UUID.randomUUID().toString();
      template.convertAndSend(EventTopology.EXCHANGE, type, publication);
      assertEquals(publication, template.receiveAndConvert("msc.planning.v1", 5000));
    }
  }

  @Test
  void stateHistoryAndOutboxCommitTogetherOrNotAtAll() {
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  store.create("counter", "a", new Counter(1));
                  store.event("CounterCreated", "a", 1, UUID.randomUUID(), null, new Counter(1));
                  throw new IllegalStateException("simulate rollback");
                }));
    assertTrue(store.find("counter", "a", Counter.class).isEmpty());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    store.transaction(
        () -> {
          store.create("counter", "a", new Counter(1));
          store.event("CounterCreated", "a", 1, UUID.randomUUID(), null, new Counter(1));
          return null;
        });
    store.transaction(
        () -> {
          store.update("counter", "a", 1, new Counter(2));
          return null;
        });
    assertEquals(1, store.history("counter", "a").getFirst().body().get("count").asInt());
    assertEquals(2, store.require("counter", "a", Counter.class).body().count());
    // A new store instance models application restart against the same durable database.
    assertEquals(
        2,
        new StateStore(db, tx, json, () -> MissionInstant.tai(100))
            .require("counter", "a", Counter.class)
            .version());
  }

  @Test
  void optimisticCommitAllowsOnlyOneConcurrentWriter() throws Exception {
    store.transaction(() -> store.create("counter", "a", new Counter(1)));
    var barrier = new CyclicBarrier(2);
    Callable<Boolean> writer =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            store.transaction(() -> store.update("counter", "a", 1, new Counter(2)));
            return true;
          } catch (ApiException conflict) {
            return false;
          }
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(writer);
      var b = pool.submit(writer);
      assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(2, store.history("counter", "a").size());
  }

  @Test
  void idempotencyIsCanonicalAndDetectsChangedRequest() {
    var calls = new AtomicInteger();
    var a =
        store.idempotent(
            "create", "key", Map.of("a", 1, "b", 2), () -> new Counter(calls.incrementAndGet()));
    var reverse = new LinkedHashMap<String, Integer>();
    reverse.put("b", 2);
    reverse.put("a", 1);
    var b = store.idempotent("create", "key", reverse, () -> new Counter(calls.incrementAndGet()));
    assertEquals(a, b);
    assertEquals(1, calls.get());
    assertThrows(
        ApiException.class,
        () -> store.idempotent("create", "key", Map.of("a", 2), () -> new Counter(99)));
  }

  @Test
  void publisherConfirmsAndInboxSuppressDuplicateDelivery() {
    var event =
        store.transaction(
            () -> store.event("CounterCreated", "a", 1, UUID.randomUUID(), null, new Counter(1)));
    var publisher = new OutboxPublisher(db, tx, template);
    publisher.publish();
    var first = template.receive("platform-test", 5000);
    assertNotNull(first);
    assertEquals(event.eventId().toString(), first.getMessageProperties().getMessageId());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class));
    // Broker accepted, but publisher crashed before recording it: resend same immutable event ID.
    db.update("UPDATE outbox SET published_at=NULL");
    publisher.publish();
    var duplicate = template.receive("platform-test", 5000);
    assertNotNull(duplicate);
    var effects = new AtomicInteger();
    for (var message : List.of(first, duplicate)) {
      var envelope =
          json.read(
              new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8),
              ServiceEvent.class);
      store.transaction(
          () -> {
            if (store.receive(envelope.eventId())) {
              store.create("counter", "a", new Counter(effects.incrementAndGet()));
            }
            return null;
          });
    }
    assertEquals(1, effects.get());
    assertEquals(1, store.require("counter", "a", Counter.class).body().count());
  }

  @Test
  void consumerFailureRollsBackInboxSoRedeliveryCanRecover() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  assertTrue(store.receive(id));
                  store.create("counter", "a", new Counter(1));
                  throw new IllegalStateException("failed handler");
                }));
    assertTrue(store.transaction(() -> store.receive(id)));
    assertTrue(store.find("counter", "a", Counter.class).isEmpty());
  }

  @Test
  void mutationsOutsideTransactionFail() {
    assertThrows(IllegalStateException.class, () -> store.create("counter", "bad", new Counter(1)));
  }

  private msc.domain.planning.ScheduleKey scheduleKey() {
    return new msc.domain.planning.ScheduleKey(
        new msc.domain.shared.Ids.SpacecraftId("sat"),
        new msc.domain.time.TimeWindow(MissionInstant.tai(0), MissionInstant.tai(100)));
  }

  private msc.domain.planning.PlanCandidate scheduleCandidate(String id, long start) {
    var definition =
        new msc.domain.missiondefinition.ActivityDefinition(
            new msc.domain.shared.Ids.ActivityDefinitionId("image"),
            1,
            "IMAGE",
            true,
            Set.of(new msc.domain.shared.Ids.ResourceId("payload")),
            Set.of(msc.domain.anomaly.MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            msc.domain.missiondefinition.AuthorityPolicy.RiskClass.LOW,
            "template:v1");
    return msc.domain.planning.PlanCandidate.propose(
        new msc.domain.shared.Ids.CandidateId(id),
        new msc.domain.shared.Ids.PlanningRunId("run-" + id),
        new msc.domain.shared.Ids.RequestId("request-" + id),
        scheduleKey().spacecraftId(),
        new msc.domain.shared.Ids.ActivityId("activity-" + id),
        new msc.domain.time.TimeWindow(MissionInstant.tai(start), MissionInstant.tai(start + 10)),
        definition,
        msc.domain.anomaly.MissionPhase.ROUTINE,
        "NOMINAL",
        new msc.domain.planning.FeasibilityEvaluation(true, "repository-test-only"));
  }

  private JdbcScheduleRepository separateScheduleRepository() {
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    return new JdbcScheduleRepository(
        new StateStore(
            new JdbcTemplate(data),
            new TransactionTemplate(new DataSourceTransactionManager(data)),
            json,
            () -> MissionInstant.tai(100)),
        json,
        new JdbcTemplate(data));
  }

  @Test
  void scheduleSnapshotsSurviveNewConnectionsAndFutureWithdrawal() {
    var repo = new JdbcScheduleRepository(store, json, db);
    var first =
        msc.domain.planning.MissionSchedule.empty(scheduleKey())
            .commit(
                scheduleCandidate("a", 10),
                new msc.domain.shared.Ids.AssignmentId("a"),
                MissionInstant.tai(0));
    repo.commit(0, first);
    var restarted = separateScheduleRepository();
    assertEquals(first.snapshot(), restarted.latest(scheduleKey()).orElseThrow().snapshot());
    var withdrawn =
        first.withdrawFuture(
            new msc.domain.shared.Ids.RequestId("request-a"), MissionInstant.tai(0));
    restarted.commit(1, withdrawn);
    assertEquals(first.snapshot(), repo.version(scheduleKey(), 1).orElseThrow().snapshot());
    assertTrue(repo.latest(scheduleKey()).orElseThrow().activities().isEmpty());
    assertEquals(2, repo.latest(scheduleKey()).orElseThrow().version());
  }

  @Test
  void differentHorizonCannotReopenFrozenTimeButRetainedHistoryRemainsWritable() {
    var repo = new JdbcScheduleRepository(store, json, db);
    var first =
        msc.domain.planning.MissionSchedule.empty(scheduleKey())
            .commit(
                scheduleCandidate("frozen-a", 80),
                new msc.domain.shared.Ids.AssignmentId("frozen-a"),
                MissionInstant.tai(50));
    repo.commit(0, first);
    var secondKey =
        new msc.domain.planning.ScheduleKey(
            scheduleKey().spacecraftId(),
            new msc.domain.time.TimeWindow(MissionInstant.tai(10), MissionInstant.tai(200)));
    var bypass =
        msc.domain.planning.MissionSchedule.empty(secondKey)
            .commit(
                scheduleCandidate("frozen-bypass", 40),
                new msc.domain.shared.Ids.AssignmentId("frozen-bypass"),
                MissionInstant.tai(10));
    assertThrows(IllegalArgumentException.class, () -> repo.commit(0, bypass));
    assertTrue(repo.latest(secondKey).isEmpty());
    var second =
        msc.domain.planning.MissionSchedule.empty(secondKey)
            .commit(
                scheduleCandidate("frozen-b", 60),
                new msc.domain.shared.Ids.AssignmentId("frozen-b"),
                MissionInstant.tai(10));
    repo.commit(0, second);
    var advanced =
        first.commit(
            scheduleCandidate("frozen-a2", 90),
            new msc.domain.shared.Ids.AssignmentId("frozen-a2"),
            MissionInstant.tai(75));
    repo.commit(1, advanced);
    // The existing activity at60 is historical; only newly introduced work is checked.
    var next =
        second.commit(
            scheduleCandidate("frozen-b2", 110),
            new msc.domain.shared.Ids.AssignmentId("frozen-b2"),
            MissionInstant.tai(10));
    repo.commit(1, next);
    assertEquals(2, repo.latest(secondKey).orElseThrow().activities().size());
    assertEquals(second.snapshot(), repo.version(secondKey, 1).orElseThrow().snapshot());
  }

  @Test
  void differentHorizonWritersCannotBothClaimTheSameSpacecraftResource() throws Exception {
    var firstKey = scheduleKey();
    var secondKey =
        new msc.domain.planning.ScheduleKey(
            firstKey.spacecraftId(),
            new msc.domain.time.TimeWindow(MissionInstant.tai(5), MissionInstant.tai(200)));
    var first =
        msc.domain.planning.MissionSchedule.empty(firstKey)
            .commit(
                scheduleCandidate("cross-a", 10),
                new msc.domain.shared.Ids.AssignmentId("cross-a"),
                MissionInstant.tai(5));
    var second =
        msc.domain.planning.MissionSchedule.empty(secondKey)
            .commit(
                scheduleCandidate("cross-b", 15),
                new msc.domain.shared.Ids.AssignmentId("cross-b"),
                MissionInstant.tai(5));
    var barrier = new CyclicBarrier(2);
    var winners = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<?>>();
      for (var proposal : List.of(first, second)) {
        jobs.add(
            pool.submit(
                () -> {
                  var independent = separateScheduleRepository();
                  barrier.await();
                  try {
                    independent.commit(0, proposal);
                    winners.incrementAndGet();
                  } catch (IllegalArgumentException conflict) {
                    assertTrue(conflict.getMessage().contains("across schedule horizons"));
                  }
                  return null;
                }));
      }
      for (var job : jobs) job.get(20, TimeUnit.SECONDS);
    }
    assertEquals(1, winners.get());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_head WHERE kind='mission-schedule'", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM state_history WHERE kind='mission-schedule'", Integer.class));
  }

  @Test
  void scheduleLookupUsesCurrentHeadsAndExactHalfOpenNanosecondBounds() {
    var repo = new JdbcScheduleRepository(store, json, db);
    var key = scheduleKey();
    var first =
        msc.domain.planning.MissionSchedule.empty(key)
            .commit(
                scheduleCandidate("lookup-a", 10),
                new msc.domain.shared.Ids.AssignmentId("lookup-a"),
                MissionInstant.tai(0));
    repo.commit(0, first);
    var laterKey =
        new msc.domain.planning.ScheduleKey(
            key.spacecraftId(),
            new msc.domain.time.TimeWindow(MissionInstant.tai(20), MissionInstant.tai(200)));
    var later =
        msc.domain.planning.MissionSchedule.empty(laterKey)
            .commit(
                scheduleCandidate("lookup-b", 20),
                new msc.domain.shared.Ids.AssignmentId("lookup-b"),
                MissionInstant.tai(20));
    repo.commit(0, later);
    var afterFirst =
        new msc.domain.time.TimeWindow(
            MissionInstant.tai(100), new MissionInstant(100, 1, msc.domain.time.TimeScale.TAI));
    assertEquals(
        List.of(laterKey),
        repo.overlapping(key.spacecraftId(), afterFirst).stream()
            .map(msc.domain.planning.MissionSchedule::key)
            .toList());
    var beforeEnd =
        new msc.domain.time.TimeWindow(
            new MissionInstant(99, 999999999, msc.domain.time.TimeScale.TAI),
            MissionInstant.tai(100));
    assertEquals(2, repo.overlapping(key.spacecraftId(), beforeEnd).size());
    assertEquals(2, repo.future(key.spacecraftId(), MissionInstant.tai(0)).size());
    assertEquals(
        List.of(laterKey),
        repo.future(key.spacecraftId(), MissionInstant.tai(100)).stream()
            .map(msc.domain.planning.MissionSchedule::key)
            .toList());
    assertTrue(repo.future(key.spacecraftId(), MissionInstant.tai(200)).isEmpty());
    assertTrue(
        repo.future(new msc.domain.shared.Ids.SpacecraftId("other"), MissionInstant.tai(0))
            .isEmpty());
    assertTrue(
        repo.overlapping(new msc.domain.shared.Ids.SpacecraftId("other"), beforeEnd).isEmpty());
    var withdrawn =
        first.withdrawFuture(
            new msc.domain.shared.Ids.RequestId("request-lookup-a"), MissionInstant.tai(0));
    repo.commit(1, withdrawn);
    var current = repo.overlapping(key.spacecraftId(), beforeEnd).getFirst();
    assertEquals(2, current.version());
    assertTrue(current.activities().isEmpty());
    assertEquals(1, repo.version(key, 1).orElseThrow().activities().size());
  }

  @Test
  void independentScheduleWritersHaveOnlyOneCasWinner() throws Exception {
    var repo = new JdbcScheduleRepository(store, json, db);
    var other = separateScheduleRepository();
    var first =
        msc.domain.planning.MissionSchedule.empty(scheduleKey())
            .commit(
                scheduleCandidate("a", 10),
                new msc.domain.shared.Ids.AssignmentId("a"),
                MissionInstant.tai(0));
    repo.commit(0, first);
    var nextA =
        first.commit(
            scheduleCandidate("b", 30),
            new msc.domain.shared.Ids.AssignmentId("b"),
            MissionInstant.tai(0));
    var nextB =
        first.commit(
            scheduleCandidate("c", 50),
            new msc.domain.shared.Ids.AssignmentId("c"),
            MissionInstant.tai(0));
    var barrier = new CyclicBarrier(2);
    var winners = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.of(
              pool.submit(
                  () -> {
                    barrier.await();
                    try {
                      repo.commit(1, nextA);
                      winners.incrementAndGet();
                    } catch (msc.ports.ScheduleRepository.VersionConflict expected) {
                    }
                    return null;
                  }),
              pool.submit(
                  () -> {
                    barrier.await();
                    try {
                      other.commit(1, nextB);
                      winners.incrementAndGet();
                    } catch (msc.ports.ScheduleRepository.VersionConflict expected) {
                    }
                    return null;
                  }));
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, winners.get());
    assertEquals(2, repo.latest(scheduleKey()).orElseThrow().version());
    assertEquals(first.snapshot(), repo.version(scheduleKey(), 1).orElseThrow().snapshot());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM state_history WHERE kind='mission-schedule'", Integer.class));
  }

  @Test
  void schedulePublicationJoinsTheOwningOutboxTransactionAndRejectsRewrites() {
    var repo = new JdbcScheduleRepository(store, json, db);
    var first =
        msc.domain.planning.MissionSchedule.empty(scheduleKey())
            .commit(
                scheduleCandidate("a", 10),
                new msc.domain.shared.Ids.AssignmentId("a"),
                MissionInstant.tai(0));
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                () -> {
                  repo.commit(0, first);
                  store.event(
                      "ScheduleVersionCommitted",
                      "s",
                      1,
                      UUID.randomUUID(),
                      null,
                      first.snapshot());
                  throw new IllegalStateException("rollback");
                }));
    assertTrue(repo.latest(scheduleKey()).isEmpty());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    repo.commit(0, first);
    var activity = first.activities().getFirst();
    var altered =
        new msc.domain.planning.ScheduledActivity(
            activity.id(),
            activity.definitionId(),
            activity.definitionVersion(),
            new msc.domain.time.TimeWindow(MissionInstant.tai(10), MissionInstant.tai(25)),
            activity.exclusiveResources());
    var forged =
        msc.domain.planning.MissionSchedule.restore(
            new msc.domain.planning.MissionSchedule.Snapshot(
                scheduleKey(),
                2,
                MissionInstant.tai(0),
                List.of(altered),
                first.assignments(),
                msc.domain.planning.ResourceValidation.notEvaluated()));
    assertThrows(IllegalArgumentException.class, () -> repo.commit(1, forged));
    assertEquals(1, repo.latest(scheduleKey()).orElseThrow().version());
  }
}
