package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.contracts.PointingContracts.RequiredTargetPointingQuery;
import msc.contracts.PointingContracts.RequiredTargetPointingResult;
import msc.contracts.PointingContracts.RequiredTargetPointingSample;
import msc.contracts.PointingContracts.RequiredTargetPointingScope;
import msc.domain.flightdynamics.Trajectory.InitialState;
import msc.orbit.GeneralPerturbationsAdapter;
import msc.orbit.KeplerianOrbitAdapter;
import msc.orbit.OrekitReferenceFrames;
import msc.orbit.RequiredTargetPointingCalculator;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * {@code REQUIRED_TARGET_POINTING} owner API: for a fixed ground target and one owner orbit
 * solution, the sampled line-of-sight profile a sensor would have to take. Geometric prediction
 * only -- not the spacecraft's actual attitude, not a commanded boresight, not AOI sensor coverage,
 * not attitude feasibility, and not wired into Planning's {@code AOI_SENSOR_COVERAGE} or {@code
 * ATTITUDE_SEQUENCE} gates by this class. See {@code docs/backend/required-target-pointing.md} and
 * {@code msc.contracts.PointingContracts} for the full scope, and {@code
 * IlluminationApi#spacecraftEclipse} for the dual-lookup discipline this mirrors.
 */
@RestController
public class RequiredTargetPointingApi {
  private final StateStore store;
  private final OrekitReferenceFrames references;
  private final Json json;

  public RequiredTargetPointingApi(StateStore store, OrekitReferenceFrames references, Json json) {
    this.store = store;
    this.references = references;
    this.json = json;
  }

  public record Request(String solutionId, RequiredTargetPointingQuery query) {
    public Request {
      msc.domain.shared.Checks.text(solutionId);
      Objects.requireNonNull(query);
    }
  }

  @PostMapping({"/api/required-target-pointing", "/internal/required-target-pointing"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode requiredTargetPointing(
      @RequestBody Request request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "required-target-pointing:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();

    // Dual lookup, mirroring OrbitApi.input(id) and IlluminationApi#spacecraftEclipse exactly: a
    // Cartesian input ("orbit") and a persisted public-GP snapshot ("public-orbit") never
    // legitimately share a solution id (OrbitApi.create and PublicOrbitApi.ingest each lock and
    // check the other kind before writing), so both present is ambiguous identity, not a valid
    // state, and fails closed with 409 -- never a silently preferred kind. Neither present fails
    // closed with 404.
    var cartesian = store.find("orbit", request.solutionId(), InitialState.class);
    var gp = store.find("public-orbit", request.solutionId(), Snapshot.class);
    if (cartesian.isPresent() && gp.isPresent())
      throw ApiException.conflict("Ambiguous orbit solution identity");

    String spacecraftId;
    String orbitPropagationModel;
    String orbitSourceHash;
    RequiredTargetPointingCalculator.Profile computed;
    var calculator = new RequiredTargetPointingCalculator(references);
    var q = request.query();
    if (cartesian.isPresent()) {
      var initial = cartesian.get().body();
      // Source consistency: the store key and the body's own solution id must agree, mirroring
      // the equivalent check the GP branch below performs against snapshot.id(). The store key
      // and the body should always agree, but that assumption is exactly what is worth asserting
      // -- an unchecked mismatch would silently attribute a profile to the wrong solution.
      if (!request.solutionId().equals(initial.solutionId()))
        throw ApiException.invalid("Orbit solution binding mismatch");
      spacecraftId = initial.spacecraftId();
      orbitPropagationModel = KeplerianOrbitAdapter.MODEL;
      // No raw external source document exists for a Cartesian input; the canonical JSON
      // fingerprint of the validated record is the closest exact "source hash" available. See
      // PointingContracts.RequiredTargetPointingResult#orbitSourceHash javadoc.
      orbitSourceHash = json.fingerprint(initial);
      computed =
          calculator.profile(
              initial, q.target(), q.horizon(), q.stepSeconds(), q.minimumElevationDegrees());
    } else if (gp.isPresent()) {
      var snapshot = gp.get().body();
      // Source consistency, mirroring PublicOrbitApi.ingest's own binding check and
      // IlluminationApi#spacecraftEclipse's own snapshot binding check.
      if (!request.solutionId().equals(snapshot.id())
          || snapshot.elements() == null
          || snapshot.rawSha256() == null
          || !snapshot.rawSha256().matches("[a-f0-9]{64}")
          || !snapshot
              .id()
              .equals("gp-" + snapshot.elements().noradId() + "-" + snapshot.rawSha256()))
        throw ApiException.invalid("Reference snapshot binding mismatch");
      // Owner invariant: the same "norad-<id>" prefix PublicOrbitApi.designate enforces.
      spacecraftId = "norad-" + snapshot.elements().noradId();
      orbitPropagationModel = GeneralPerturbationsAdapter.MODEL;
      // The raw external-source content hash already carried by the snapshot, already validated
      // for binding above -- see PointingContracts.RequiredTargetPointingResult#orbitSourceHash.
      orbitSourceHash = snapshot.rawSha256();
      computed =
          calculator.profile(
              snapshot.elements(),
              q.target(),
              q.horizon(),
              q.stepSeconds(),
              q.minimumElevationDegrees());
    } else {
      throw ApiException.missing("orbit not found");
    }

    List<RequiredTargetPointingSample> samples =
        computed.samples().stream().map(this::toContract).toList();
    var result =
        new RequiredTargetPointingResult(
            request.solutionId(),
            spacecraftId,
            orbitPropagationModel,
            computed.pointingModel(),
            orbitSourceHash,
            references.digest(),
            q,
            samples,
            RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE);

    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var saved = store.create("required-target-pointing", id, result);
          store.event(
              "RequiredTargetPointingComputed",
              id,
              saved.version(),
              UUID.randomUUID(),
              null,
              result);
          return saved;
        });
  }

  /** Field-for-field mapping from the calculator's plain output record to the stored contract. */
  private RequiredTargetPointingSample toContract(RequiredTargetPointingCalculator.Sample s) {
    return new RequiredTargetPointingSample(
        s.instant(),
        s.satellitePositionEarthFixedMeters(),
        s.lineOfSightEarthFixedUnit(),
        s.lineOfSightInertialUnit(),
        s.requiredOffNadirDegrees(),
        s.slantRangeMeters(),
        s.subSatellitePoint(),
        s.target(),
        s.topocentricElevationDegrees(),
        s.topocentricAzimuthDegrees(),
        s.targetVisible());
  }

  @GetMapping({"/api/required-target-pointing/{id}", "/internal/required-target-pointing/{id}"})
  public RequiredTargetPointingResult requiredTargetPointingResult(@PathVariable String id) {
    return store.require("required-target-pointing", id, RequiredTargetPointingResult.class).body();
  }
}
