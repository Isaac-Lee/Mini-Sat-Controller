package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import msc.contracts.AgilityContracts;
import msc.contracts.ManeuverContracts;
import msc.contracts.ManeuverContracts.*;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.PointingContracts.RequiredTargetPointingSample;
import msc.domain.flightdynamics.RestToRestSlew;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Kinematic rest-to-rest slew estimates between stored required-target-pointing samples, bounded
 * by a pinned approved agility model. Scope {@code KINEMATIC_MODEL_NO_DYNAMICS}: no attitude
 * determination, control, tracking or settling claim. See {@code
 * docs/backend/attitude-maneuver-estimates.md} and ADR-0015.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
public class ManeuverEstimateApi {
  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public ManeuverEstimateApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  @PostMapping({"/api/maneuver-estimates", "/internal/maneuver-estimates"})
  public JsonNode estimate(
      @RequestBody Request request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "maneuver-estimate:" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();

    String craft =
        java.net.URLEncoder.encode(request.spacecraftId(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    var agilityState =
        http.get(
            "mission-definition",
            "/internal/agility-models/" + craft + "/versions/" + request.agilityModelVersion(),
            JsonNode.class);
    var agility = json.convert(agilityState.get("body"), AgilityContracts.Model.class);
    if (agilityState.path("version").asLong() != request.agilityModelVersion()
        || !agility.spacecraftId().equals(request.spacecraftId()))
      throw ApiException.invalid("Agility model binding mismatch");

    List<RequiredTargetPointingSample> samples = new ArrayList<>();
    for (var target : request.targets()) samples.add(sample(request.spacecraftId(), target));

    List<Transition> transitions = new ArrayList<>();
    for (int i = 1; i < samples.size(); i++) {
      var from = samples.get(i - 1);
      var to = samples.get(i);
      var profile =
          RestToRestSlew.profile(
              RestToRestSlew.angleDegrees(
                  from.lineOfSightInertialUnit(), to.lineOfSightInertialUnit()),
              agility.slewRateDegreesPerSecond(),
              request.maximumAccelerationDegreesPerSecondSquared(),
              agility.settlingSeconds());
      double available = secondsBetween(from.instant(), to.instant());
      var reasons = EnumSet.noneOf(Infeasibility.class);
      if (profile.totalSeconds() > available) reasons.add(Infeasibility.INSUFFICIENT_TIME);
      if (Math.max(from.requiredOffNadirDegrees(), to.requiredOffNadirDegrees())
          > agility.maximumOffNadirDegrees()) reasons.add(Infeasibility.OFF_NADIR_LIMIT_EXCEEDED);
      if (!from.targetVisible() || !to.targetVisible())
        reasons.add(Infeasibility.TARGET_NOT_VISIBLE);
      transitions.add(
          new Transition(
              i - 1,
              i,
              profile.angleDegrees(),
              profile.shape(),
              profile.peakRateDegreesPerSecond(),
              profile.slewSeconds(),
              profile.settlingSeconds(),
              profile.totalSeconds(),
              available,
              reasons.isEmpty(),
              reasons));
    }
    var plan =
        new Plan(
            request.spacecraftId(),
            ManeuverContracts.ENVIRONMENT,
            ManeuverContracts.FRAME,
            ManeuverContracts.MODEL,
            request.agilityModelVersion(),
            agility,
            request.maximumAccelerationDegreesPerSecondSquared(),
            request.targets(),
            transitions,
            transitions.stream().allMatch(Transition::feasible),
            Scope.KINEMATIC_MODEL_NO_DYNAMICS);
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var saved = store.create("maneuver-estimate", id, plan);
          store.event("ManeuverEstimated", id, saved.version(), UUID.randomUUID(), null, plan);
          return saved;
        });
  }

  /** The stored sample at exactly the requested instant; no interpolation between samples. */
  private RequiredTargetPointingSample sample(String spacecraftId, PointingTarget target) {
    var result =
        store
            .require(
                "required-target-pointing",
                target.requiredTargetPointingId(),
                RequiredTargetPointingResult.class)
            .body();
    if (!result.spacecraftId().equals(spacecraftId))
      throw ApiException.invalid("Required-target-pointing result belongs to another spacecraft");
    return result.samples().stream()
        .filter(s -> s.instant().equals(target.instant()))
        .findFirst()
        .orElseThrow(() -> ApiException.invalid("No pointing sample at the requested instant"));
  }

  /** Differences whole seconds before converting, so large TAI counts keep nanosecond detail. */
  private static double secondsBetween(MissionInstant from, MissionInstant to) {
    return (to.seconds() - from.seconds()) + (to.nanos() - from.nanos()) / 1e9;
  }

  @GetMapping({"/api/maneuver-estimates/{id}", "/internal/maneuver-estimates/{id}"})
  public Plan get(@PathVariable String id) {
    return store.require("maneuver-estimate", id, Plan.class).body();
  }
}
