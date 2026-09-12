package msc.application;

import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.planning.*;
import msc.domain.shared.Ids.*;
import msc.domain.tasking.*;
import msc.domain.time.*;
import msc.ports.*;

/** Deterministic architecture proof with caller-supplied, explicitly labeled planning fixtures. */
public final class PlanObservation {
  private final Clock clock;
  private final ScheduleRepository schedules;

  public PlanObservation(Clock clock, ScheduleRepository schedules) {
    this.clock = Objects.requireNonNull(clock);
    this.schedules = Objects.requireNonNull(schedules);
  }

  public record Fixture(
      String target,
      AoiId aoi,
      ScheduleKey scheduleKey,
      TimeWindow opportunity,
      ActivityDefinition activityDefinition,
      PlanningDataSnapshot inputs,
      FeasibilityEvaluation evaluation) {
    public Fixture {
      msc.domain.shared.Checks.text(target);
      Objects.requireNonNull(aoi);
      Objects.requireNonNull(scheduleKey);
      Objects.requireNonNull(opportunity);
      Objects.requireNonNull(activityDefinition);
      Objects.requireNonNull(inputs);
      Objects.requireNonNull(evaluation);
    }
  }

  public record Ids(
      RequestId request,
      PlanningRunId run,
      CandidateId candidate,
      ActivityId activity,
      AssignmentId assignment) {
    public Ids {
      Objects.requireNonNull(request);
      Objects.requireNonNull(run);
      Objects.requireNonNull(candidate);
      Objects.requireNonNull(activity);
      Objects.requireNonNull(assignment);
    }
  }

  public record Result(ObservationRequest request, PlanningRun run, MissionSchedule schedule) {}

  public Result execute(MissionIntent intent, Fixture fixture, Ids ids) {
    if (!intent.target().equals(fixture.target()))
      throw new IllegalArgumentException("No deterministic AOI fixture for target");
    MissionInstant startedAt = clock.now();
    startedAt.requireTai();
    if (fixture.opportunity().start().compareTo(startedAt) < 0)
      throw new IllegalArgumentException("Opportunity is in the past");
    var request =
        new ObservationRequest(
                ids.request(),
                fixture.aoi(),
                1,
                "fixture: one Daejeon acquisition",
                Optional.empty(),
                0,
                ObservationRequest.InteractionPreference.AUTO,
                ObservationRequest.Status.RECEIVED)
            .accept();
    var candidate =
        PlanCandidate.propose(
            ids.candidate(),
            ids.run(),
            request.id(),
            fixture.scheduleKey().spacecraftId(),
            ids.activity(),
            fixture.opportunity(),
            fixture.activityDefinition(),
            MissionPhase.ROUTINE,
            "NOMINAL",
            fixture.evaluation());
    var run =
        new PlanningRun(
            ids.run(),
            request.id(),
            startedAt,
            fixture.inputs(),
            List.of(
                new Opportunity(
                    fixture.scheduleKey().spacecraftId(), fixture.aoi(), fixture.opportunity())),
            List.of(candidate),
            List.of(
                new DecisionRecord(
                    candidate.id(),
                    "Deterministic fixture only; no physical feasibility claimed")));
    var previous =
        schedules
            .latest(fixture.scheduleKey())
            .orElseGet(() -> MissionSchedule.empty(fixture.scheduleKey()));
    var next = previous.commit(candidate, ids.assignment(), previous.frozenUntil());
    schedules.commit(previous.version(), next);
    return new Result(request, run, next);
  }
}
