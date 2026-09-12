package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.IlluminationContracts;
import msc.contracts.IlluminationContracts.*;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.domain.time.TimeWindow;
import msc.orbit.GeneralPerturbationsAdapter;
import msc.orbit.KeplerianOrbitAdapter;
import msc.orbit.OrekitIlluminationPredictor;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Target (ground-point) sun-elevation illumination and spacecraft eclipse/sunlit owner API.
 * Geometric prediction only; not AOI sensor coverage, attitude feasibility, resource forecasting,
 * booking or command authority, and not wired into Planning's ILLUMINATION gate by this class.
 * See {@code docs/backend/illumination.md} for the accuracy scope and the binding request.
 */
@RestController
public class IlluminationApi {
  private final StateStore store;
  private final OrekitReferenceFrames references;
  private final GeneralPerturbationsAdapter propagation;

  public IlluminationApi(StateStore store, OrekitReferenceFrames references) {
    this.store = store;
    this.references = references;
    this.propagation = new GeneralPerturbationsAdapter(references);
  }

  public record TargetIlluminationRequest(String spacecraftId, TargetIlluminationQuery query) {
    public TargetIlluminationRequest {
      msc.domain.shared.Checks.text(spacecraftId);
      Objects.requireNonNull(query);
    }
  }

  @PostMapping({"/api/target-illumination", "/internal/target-illumination"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode targetIllumination(
      @RequestBody TargetIlluminationRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "target-illumination:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    var predictor = new OrekitIlluminationPredictor(references);
    var samples = request.query().aoi().fivePointSample();
    var points = new ArrayList<SampledPointIllumination>();
    var perPointWindows = new ArrayList<List<TimeWindow>>();
    OrekitIlluminationPredictor.ToleranceSettings tolerances = null;
    for (var sample : samples) {
      var computed =
          predictor.targetIllumination(
              request.query().horizon(),
              sample.latitudeDegrees(),
              sample.longitudeDegrees(),
              sample.altitudeMeters(),
              request.query().minimumSunElevationDegrees());
      points.add(
          new SampledPointIllumination(
              sample.id(),
              sample.latitudeDegrees(),
              sample.longitudeDegrees(),
              computed.illuminatedWindows()));
      perPointWindows.add(computed.illuminatedWindows());
      tolerances = computed.tolerances();
    }
    var intersection = IlluminationContracts.intersectAll(perPointWindows);
    var result =
        new TargetIlluminationResult(
            request.spacecraftId(),
            OrekitIlluminationPredictor.SOLAR_MODEL,
            OrekitIlluminationPredictor.SOLAR_MODEL_ACCURACY_NOTE,
            references.digest(),
            request.query(),
            tolerances.rootToleranceSeconds(),
            tolerances.maximumCheckSeconds(),
            points,
            intersection,
            AoiIlluminationScope.SAMPLED_POINTS_ONLY);
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var saved = store.create("target-illumination", id, result);
          store.event(
              "TargetIlluminationPredicted",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              result);
          return saved;
        });
  }

  @GetMapping({"/api/target-illumination/{id}", "/internal/target-illumination/{id}"})
  public TargetIlluminationResult targetIlluminationResult(@PathVariable String id) {
    return store.require("target-illumination", id, TargetIlluminationResult.class).body();
  }

  public record EclipseRequest(String solutionId, TimeWindow horizon) {
    public EclipseRequest {
      msc.domain.shared.Checks.text(solutionId);
      Objects.requireNonNull(horizon);
    }
  }

  @PostMapping({"/api/spacecraft-eclipse", "/internal/spacecraft-eclipse"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode spacecraftEclipse(
      @RequestBody EclipseRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "spacecraft-eclipse:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    // Dual lookup, mirroring OrbitApi.input(id): a Cartesian input ("orbit") and a persisted
    // public-GP snapshot ("public-orbit") never legitimately share a solution id (OrbitApi.create
    // and PublicOrbitApi.ingest each lock and check the other kind before writing), so both
    // present is ambiguous identity, not a valid state, and fails closed with 409 -- never a
    // silently preferred kind. Neither present fails closed with 404.
    var cartesian = store.find("orbit", request.solutionId(), InitialState.class);
    var gp = store.find("public-orbit", request.solutionId(), Snapshot.class);
    if (cartesian.isPresent() && gp.isPresent())
      throw ApiException.conflict("Ambiguous orbit solution identity");
    String spacecraftId;
    String orbitPropagationModel;
    OrekitIlluminationPredictor.SpacecraftEclipse computed;
    if (cartesian.isPresent()) {
      var initial = cartesian.get().body();
      spacecraftId = initial.spacecraftId();
      orbitPropagationModel = KeplerianOrbitAdapter.MODEL;
      computed = new OrekitIlluminationPredictor(references).spacecraftEclipse(initial, request.horizon());
    } else if (gp.isPresent()) {
      var snapshot = gp.get().body();
      // Source consistency: the resolved snapshot must actually be the one requested, mirroring
      // PublicOrbitApi.ingest's own binding check.
      if (!request.solutionId().equals(snapshot.id())
          || snapshot.elements() == null
          || snapshot.rawSha256() == null
          || !snapshot.rawSha256().matches("[a-f0-9]{64}")
          || !snapshot.id().equals("gp-" + snapshot.elements().noradId() + "-" + snapshot.rawSha256()))
        throw ApiException.invalid("Reference snapshot binding mismatch");
      // Owner invariant: the same "norad-<id>" prefix PublicOrbitApi.designate enforces.
      spacecraftId = "norad-" + snapshot.elements().noradId();
      orbitPropagationModel = GeneralPerturbationsAdapter.MODEL;
      computed = propagation.eclipse(snapshot.elements(), request.horizon());
    } else {
      throw ApiException.missing("orbit not found");
    }
    var result =
        new SpacecraftEclipseResult(
            request.solutionId(),
            spacecraftId,
            OrekitIlluminationPredictor.ECLIPSE_MODEL,
            OrekitIlluminationPredictor.ECLIPSE_MODEL_ACCURACY_NOTE,
            references.digest(),
            request.horizon(),
            computed.tolerances().rootToleranceSeconds(),
            computed.tolerances().maximumCheckSeconds(),
            computed.sunlitWindows(),
            computed.eclipseWindows(),
            Optional.of(orbitPropagationModel));
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var saved = store.create("spacecraft-eclipse", id, result);
          store.event(
              "SpacecraftEclipsePredicted",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              result);
          return saved;
        });
  }

  @GetMapping({"/api/spacecraft-eclipse/{id}", "/internal/spacecraft-eclipse/{id}"})
  public SpacecraftEclipseResult spacecraftEclipseResult(@PathVariable String id) {
    return store.require("spacecraft-eclipse", id, SpacecraftEclipseResult.class).body();
  }
}
