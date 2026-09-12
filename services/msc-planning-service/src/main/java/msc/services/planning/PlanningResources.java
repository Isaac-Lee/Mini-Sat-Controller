package msc.services.planning;

import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.PropellantContracts;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.planning.*;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.shared.Ids.SpacecraftId;
import msc.domain.time.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.*;

/** Immutable resource evidence for a proposal; publication does not reserve or release work. */
final class PlanningResources {
  record CandidateResult(
      String candidateId,
      Optional<TimeWindow> horizon,
      Optional<ResourceTimeline.Result> forecast,
      List<String> issues) {
    CandidateResult {
      horizon = horizon == null ? Optional.empty() : horizon;
      forecast = forecast == null ? Optional.empty() : forecast;
      issues = List.copyOf(issues);
    }
  }

  record Assessment(
      String runId,
      String inputAttemptId,
      String spacecraftId,
      String scope,
      String scheduleContextStatus,
      String supplyAssumption,
      String propellantInterpretation,
      List<MissionSchedule.Snapshot> scheduleHeads,
      Map<String, Evidence> activityCatalogs,
      Map<String, Evidence> activityOperationProfiles,
      List<CandidateResult> candidates) {
    Assessment {
      scheduleHeads = List.copyOf(scheduleHeads);
      activityCatalogs = Map.copyOf(activityCatalogs);
      activityOperationProfiles =
          activityOperationProfiles == null ? Map.of() : Map.copyOf(activityOperationProfiles);
      candidates = List.copyOf(candidates);
    }
  }

  private final Json json;

  PlanningResources(Json json) {
    this.json = json;
  }

  /** Caller owns the live intake transaction. Acquire craft locks in sorted craft order. */
  void publish(
      StateStore store,
      JdbcScheduleRepository repository,
      Attempt attempt,
      Asset asset,
      PlanningRuns.Published run) {
    repository.lockSpacecraft(new SpacecraftId(asset.spacecraftId()));
    List<MissionSchedule> heads = List.of();
    boolean headsRead = false;
    try {
      var source = telemetry(asset);
      var start = source.accepted().orElseThrow().frame().observedAt();
      heads = repository.future(new SpacecraftId(asset.spacecraftId()), start);
      headsRead = true;
    } catch (IllegalArgumentException | NoSuchElementException invalid) {
      // A historical/incomplete source is retained as an explicit unevaluated assessment below.
    }
    var profiles = new TreeMap<String, Evidence>();
    var operations = new TreeMap<String, Evidence>();
    for (var head : heads)
      for (var activity : head.activities()) {
        store
            .find("planning-activity-catalog", activity.id().value(), Evidence.class)
            .ifPresent(state -> profiles.put(activity.id().value(), state.body()));
        store
            .find("planning-activity-operation-profiles", activity.id().value(), Evidence.class)
            .ifPresent(state -> operations.put(activity.id().value(), state.body()));
      }
    var result = derive(attempt, asset, run, heads, profiles, headsRead, operations);
    store.create("planning-resource-assessment", run.id(), result);
  }

  private Estimate telemetry(Asset asset) {
    var source =
        json.convert(
            asset.inputs().get(Input.SPACECRAFT_STATE).value().path("estimate").path("body"),
            Estimate.class);
    if (source == null) throw new IllegalArgumentException("Missing telemetry evidence");
    return source;
  }

  Assessment derive(
      Attempt attempt,
      Asset asset,
      PlanningRuns.Published run,
      List<MissionSchedule> heads,
      Map<String, Evidence> profiles,
      boolean headsRead) {
    return derive(attempt, asset, run, heads, profiles, headsRead, Map.of());
  }

  Assessment derive(
      Attempt attempt,
      Asset asset,
      PlanningRuns.Published run,
      List<MissionSchedule> heads,
      Map<String, Evidence> profiles,
      boolean headsRead,
      Map<String, Evidence> operationProfiles) {
    if (!run.inputAttemptId().equals(attempt.id())
        || !run.spacecraftId().equals(asset.spacecraftId()))
      throw new IllegalArgumentException("Resource assessment run binding mismatch");
    var results = new ArrayList<CandidateResult>();
    for (var candidate : run.run().candidates()) {
      Optional<TimeWindow> horizon = Optional.empty();
      var issues = new ArrayList<String>();
      if (!headsRead) issues.add("SCHEDULE_HEADS_UNREAD");
      Optional<ResourceTimeline.Result> forecast = Optional.empty();
      try {
        var mission =
            json.convert(
                asset.inputs().get(Input.MISSION_DEFINITION).value().path("body"),
                MissionProfile.class);
        var model = json.convert(run.simulationModel().value().path("body"), Model.class);
        var catalog = json.convert(run.catalog().value(), CatalogEntry.class);
        var source = telemetry(asset);
        var propellant =
            json.convert(
                asset.inputs().get(Input.PROPELLANT).value().path("body"),
                PropellantContracts.Snapshot.class);
        if (source == null || mission == null || propellant == null)
          throw new IllegalArgumentException("Missing resource evidence");
        var frame = source.accepted().orElseThrow().frame();
        if (!source.binding().spacecraftId().equals(asset.spacecraftId())
            || !mission.spacecraftId().equals(asset.spacecraftId())
            || !model.missionDefinitionVersion().equals(mission.missionDefinitionVersion())
            || !propellant.source().equals(source)
            || !propellant
                .model()
                .missionDefinitionVersion()
                .equals(mission.missionDefinitionVersion())
            || source.binding().environment() != Environment.SIMULATION)
          throw new IllegalArgumentException("Resource source binding mismatch");
        if (source.confidence(attempt.capturedAt()) != Confidence.FRESH)
          issues.add("FRESH_RESOURCE_INITIAL_STATE_REQUIRED");
        if (candidate.activity().window().start().compareTo(frame.observedAt()) < 0)
          issues.add("CANDIDATE_PRECEDES_RESOURCE_INITIAL_STATE");
        var forecastEnd = candidate.activity().window().end();
        for (var head : heads)
          for (var activity : head.activities())
            if (activity.window().end().compareTo(forecastEnd) > 0)
              forecastEnd = activity.window().end();
        var window = new TimeWindow(frame.observedAt(), forecastEnd);
        horizon = Optional.of(window);
        var activities = new LinkedHashMap<String, ScheduledActivity>();
        for (var head : heads) {
          if (!head.key().spacecraftId().value().equals(asset.spacecraftId()))
            throw new IllegalArgumentException("Schedule spacecraft mismatch");
          for (var activity : head.activities()) {
            if (!activity.window().overlaps(window)) continue;
            if (activities.putIfAbsent(activity.id().value(), activity) != null)
              issues.add("DUPLICATE_SCHEDULE_ACTIVITY:" + activity.id().value());
            if (activity.window().overlaps(candidate.activity().window())
                && !Collections.disjoint(
                    activity.exclusiveResources(), candidate.activity().exclusiveResources()))
              issues.add("EXCLUSIVE_RESOURCE_CONFLICT:" + activity.id().value());
          }
        }
        if (activities.putIfAbsent(candidate.activity().id().value(), candidate.activity()) != null)
          issues.add("CANDIDATE_ALREADY_COMMITTED");
        var loads = new ArrayList<ResourceTimeline.Load>();
        for (var activity : activities.values()) {
          var pinned =
              activity.id().equals(candidate.activity().id())
                  ? run.catalog()
                  : profiles.get(activity.id().value());
          if (pinned == null) {
            issues.add("PINNED_RESOURCE_PROFILE_REQUIRED:" + activity.id().value());
            continue;
          }
          if (!json.fingerprint(pinned.value()).equals(pinned.sha256())) {
            issues.add("RESOURCE_CATALOG_EVIDENCE_HASH_MISMATCH:" + activity.id().value());
            continue;
          }
          var profile = json.convert(pinned.value(), CatalogEntry.class);
          if (!activity.definitionId().equals(profile.activity().id())
              || activity.definitionVersion() != profile.activity().version()
              || !activity.exclusiveResources().equals(profile.activity().exclusiveResources())) {
            issues.add("ACTIVITY_CATALOG_BINDING_MISMATCH:" + activity.id().value());
            continue;
          }
          var operationEvidence =
              activity.id().equals(candidate.activity().id())
                  ? run.operations().map(PlanningOperations.Captured::resourceProfiles).orElse(null)
                  : operationProfiles.get(activity.id().value());
          double downlinkRate = 0;
          if (operationEvidence != null) {
            try {
              var operations = new PlanningOperations(null, json);
              var effect =
                  operations.profile(operations.profiles(mission, operationEvidence), profile);
              if (effect.operation() == msc.contracts.OperationResourceContracts.Operation.DOWNLINK)
                downlinkRate = effect.downlinkMegabytesPerSecond();
            } catch (ApiException | IllegalArgumentException invalid) {
              issues.add("OPERATION_RESOURCE_EVIDENCE_INVALID:" + activity.id().value());
              continue;
            }
          } else if (!"IMAGE".equals(profile.template().operation())) {
            issues.add("OPERATION_RESOURCE_MODEL_REQUIRED:" + activity.id().value());
            continue;
          }
          double duration = seconds(activity.window().start(), activity.window().end());
          if (Math.abs(duration - profile.durationSeconds()) > 1e-9) {
            issues.add("ACTIVITY_DURATION_PROFILE_MISMATCH:" + activity.id().value());
            continue;
          }
          var start =
              activity.window().start().compareTo(window.start()) < 0
                  ? window.start()
                  : activity.window().start();
          var end =
              activity.window().end().compareTo(window.end()) > 0
                  ? window.end()
                  : activity.window().end();
          loads.add(
              new ResourceTimeline.Load(
                  activity.id().value(),
                  new TimeWindow(start, end),
                  profile.resources().powerWatts(),
                  profile.resources().generatedMegabytes() / duration,
                  downlinkRate,
                  profile.resources().propellantKilograms()));
        }
        if (loads.size() != activities.size()) issues.add("INCOMPLETE_ACTIVITY_LOAD_COVERAGE");
        if (issues.isEmpty()) {
          // Use end-of-horizon mass lower bound, then subtract all scheduled burns. This bounds
          // unmodeled loss throughout, without resetting the forecast clock at candidate start.
          var initial =
              new ResourceTimeline.Initial(
                  window.start(),
                  frame.batteryWh(),
                  frame.storageMb(),
                  propellant.lowerBoundAt(window.end()).doubleValue(),
                  "telemetry:"
                      + asset.inputs().get(Input.SPACECRAFT_STATE).sha256()
                      + "/propellant:"
                      + asset.inputs().get(Input.PROPELLANT).sha256());
          var limits =
              new ResourceTimeline.Limits(
                  mission.batteryCapacityWh(),
                  mission.minimumBatteryWh(),
                  mission.storageCapacityMb(),
                  model.minimumPropellantKg());
          String reference = "simulation-model:" + run.simulationModel().sha256();
          forecast =
              Optional.of(
                  ResourceTimeline.evaluate(
                      window,
                      initial,
                      limits,
                      List.of(model.conservativeSupply(window, reference)),
                      loads,
                      Set.of(
                          ResourceTimeline.Resource.BATTERY,
                          ResourceTimeline.Resource.STORAGE,
                          ResourceTimeline.Resource.PROPELLANT),
                      reference));
        }
      } catch (IllegalArgumentException | NoSuchElementException invalid) {
        issues.add("RESOURCE_INPUT_INVALID_OR_OUTSIDE_VALIDITY");
      }
      results.add(new CandidateResult(candidate.id().value(), horizon, forecast, issues));
    }
    return new Assessment(
        run.id(),
        attempt.id(),
        asset.spacecraftId(),
        "SIMULATION_BATTERY_STORAGE_PROPELLANT_PROPOSAL_ONLY",
        headsRead ? "CURRENT_HEADS_CAPTURED" : "SCHEDULE_HEADS_UNREAD",
        "ECLIPSE_GENERATION_LOWER_BOUND_THROUGHOUT",
        "HORIZON_END_LOWER_BOUND_MINUS_ALL_BURNS",
        heads.stream().map(MissionSchedule::snapshot).toList(),
        profiles,
        operationProfiles,
        results);
  }

  private static double seconds(MissionInstant start, MissionInstant end) {
    return Math.subtractExact(end.seconds(), start.seconds()) + (end.nanos() - start.nanos()) / 1e9;
  }
}
