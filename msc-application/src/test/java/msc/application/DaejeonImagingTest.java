package msc.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.*;
import msc.domain.planning.*;
import msc.domain.referencedata.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.*;
import msc.domain.time.*;
import msc.infrastructure.persistence.InMemoryScheduleRepository;
import msc.infrastructure.time.VirtualClock;
import msc.ports.ScheduleRepository;
import org.junit.jupiter.api.Test;

class DaejeonImagingTest {
  static PlanObservation.Fixture fixture(long start) {
    var refs =
        new EnumMap<PlanningDataSnapshot.Input, SnapshotRef>(PlanningDataSnapshot.Input.class);
    for (var input : PlanningDataSnapshot.Input.values())
      refs.put(
          input,
          new SnapshotRef(
              new SnapshotId(input.name()), 1, "fixture:" + input, MissionInstant.tai(0)));
    var definition =
        new ActivityDefinition(
            new ActivityDefinitionId("imaging"),
            1,
            "IMAGING_STRIP",
            true,
            Set.of(new ResourceId("payload")),
            Set.of(MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            AuthorityPolicy.RiskClass.LOW,
            "approved-template:v1");
    return new PlanObservation.Fixture(
        "Daejeon",
        new AoiId("DaejeonAOI"),
        new ScheduleKey(
            new SpacecraftId("sat"),
            new TimeWindow(MissionInstant.tai(0), MissionInstant.tai(100))),
        new TimeWindow(MissionInstant.tai(start), MissionInstant.tai(start + 10)),
        definition,
        new PlanningDataSnapshot(refs),
        new FeasibilityEvaluation(true, "deterministic-fixture-not-physical-validation"));
  }

  static PlanObservation.Ids ids(String suffix) {
    return new PlanObservation.Ids(
        new RequestId("request"),
        new PlanningRunId("run" + suffix),
        new CandidateId("candidate" + suffix),
        new ActivityId("activity" + suffix),
        new AssignmentId("assignment" + suffix));
  }

  @Test
  void intentReachesCommittedAssignmentAndReplanPreservesBothVersions() {
    var clock = new VirtualClock(MissionInstant.tai(1));
    var repository = new InMemoryScheduleRepository();
    var useCase = new PlanObservation(clock, repository);
    var first = useCase.execute(new MissionIntent("Daejeon"), fixture(10), ids("1"));
    clock.advance(new MissionDuration(1_000_000_000));
    var second = useCase.execute(new MissionIntent("Daejeon"), fixture(20), ids("2"));
    assertEquals(ObservationRequest.Status.ACCEPTED, first.request().status());
    assertEquals(new AoiId("DaejeonAOI"), first.request().resolvedAoi().orElseThrow());
    assertEquals(MissionInstant.tai(1), first.run().startedAt());
    assertEquals(MissionInstant.tai(2), second.run().startedAt());
    assertEquals(
        first.run().candidates().getFirst().id(),
        first.schedule().assignments().getFirst().candidateId());
    assertEquals(first.request().id(), first.schedule().assignments().getFirst().requestId());
    assertEquals(1, first.schedule().version());
    assertEquals(2, second.schedule().version());
    assertEquals(1, first.schedule().assignments().size());
    assertEquals(1, first.run().candidates().size());
    assertSame(first.schedule(), repository.version(first.schedule().key(), 1).orElseThrow());
    assertSame(second.schedule(), repository.latest(first.schedule().key()).orElseThrow());
    assertEquals(10, first.run().inputs().references().size());
  }

  @Test
  void unknownTargetAndPastOpportunityDoNotCommit() {
    var repository = new InMemoryScheduleRepository();
    var useCase = new PlanObservation(new VirtualClock(MissionInstant.tai(15)), repository);
    assertThrows(
        IllegalArgumentException.class,
        () -> useCase.execute(new MissionIntent("Seoul"), fixture(20), ids("1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> useCase.execute(new MissionIntent("Daejeon"), fixture(10), ids("1")));
    assertTrue(repository.latest(fixture(10).scheduleKey()).isEmpty());
  }

  @Test
  void concurrentWritersCannotCommitTwoHeadsFromSameVersion() throws Exception {
    var source = new InMemoryScheduleRepository();
    var candidate =
        new PlanObservation(new VirtualClock(MissionInstant.tai(0)), source)
            .execute(new MissionIntent("Daejeon"), fixture(10), ids("1"))
            .schedule();
    var destination = new InMemoryScheduleRepository();
    var barrier = new CyclicBarrier(2);
    Callable<Boolean> writer =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            destination.commit(0, candidate);
            return true;
          } catch (ScheduleRepository.VersionConflict expected) {
            return false;
          }
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var one = pool.submit(writer);
      var two = pool.submit(writer);
      assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, destination.latest(candidate.key()).orElseThrow().version());
    assertTrue(destination.version(candidate.key(), 2).isEmpty());
  }

  @Test
  void staleWriterDoesNotOverwriteHistory() {
    var repository = new InMemoryScheduleRepository();
    var first =
        new PlanObservation(new VirtualClock(MissionInstant.tai(0)), repository)
            .execute(new MissionIntent("Daejeon"), fixture(10), ids("1"));
    assertThrows(
        ScheduleRepository.VersionConflict.class, () -> repository.commit(0, first.schedule()));
    assertSame(first.schedule(), repository.latest(first.schedule().key()).orElseThrow());
  }
}
