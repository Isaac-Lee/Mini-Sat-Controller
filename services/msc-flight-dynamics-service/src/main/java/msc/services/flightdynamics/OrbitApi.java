package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.domain.flightdynamics.Trajectory.*;
import msc.domain.time.*;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.*;
import msc.ports.OrbitComputationPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrbitApi {
  public record PredictionRequest(String solutionId, TimeWindow horizon, int stepSeconds) {
    public PredictionRequest {
      msc.domain.shared.Checks.text(solutionId);
      Objects.requireNonNull(horizon);
    }
  }

  public record PredictionManifest(
      String id,
      String solutionId,
      String spacecraftId,
      String model,
      String frame,
      TimeWindow coverage,
      int stepSeconds,
      int sampleCount,
      String referenceDigest,
      String objectReference) {}

  public record EphemerisDocument(
      Prediction prediction, List<GroundPoint> groundTrack, String referenceDigest) {}

  private final StateStore store;
  private final OrbitComputationPort computation;
  private final OrekitReferenceFrames references;
  private final ObjectStorage objects;
  private final Json json;
  private final msc.ports.Clock clock;

  public OrbitApi(
      StateStore store,
      OrbitComputationPort computation,
      OrekitReferenceFrames references,
      ObjectStorage objects,
      Json json,
      msc.ports.Clock clock) {
    this.store = store;
    this.computation = computation;
    this.references = references;
    this.objects = objects;
    this.json = json;
    this.clock = clock;
  }

  @PostMapping({"/api/orbits", "/internal/orbits"})
  @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
  public JsonNode create(
      @RequestBody InitialState orbit,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    // Validate model acceptance before persisting an input solution; this does not designate it
    // operational.
    computation.predict(
        orbit,
        new TimeWindow(orbit.epoch(), orbit.epoch().plus(new MissionDuration(1_000_000_000L))),
        1);
    return store.idempotent(
        "orbit:" + actor.getName(),
        key,
        orbit,
        () -> {
          store.lock("orbit-solution:" + orbit.solutionId());
          if (store.find("public-orbit", orbit.solutionId(), JsonNode.class).isPresent())
            throw ApiException.conflict(
                "Orbit solution ID already belongs to a public GP snapshot");
          var state = store.create("orbit", orbit.solutionId(), orbit);
          store.event(
              "OrbitSolutionRecorded", state.id(), state.version(), UUID.randomUUID(), null, orbit);
          return state;
        });
  }

  @GetMapping({"/api/orbits/{id}", "/internal/orbits/{id}"})
  public InitialState orbit(@PathVariable String id) {
    return store.require("orbit", id, InitialState.class).body();
  }

  public record OrbitInput(String kind, JsonNode source) {}

  @GetMapping("/internal/orbit-inputs/{id}")
  public OrbitInput input(@PathVariable String id) {
    var cartesian = store.find("orbit", id, JsonNode.class);
    var gp = store.find("public-orbit", id, JsonNode.class);
    if (cartesian.isPresent() && gp.isPresent())
      throw ApiException.conflict("Ambiguous orbit solution identity");
    if (cartesian.isPresent()) return new OrbitInput("CARTESIAN", cartesian.get().body());
    if (gp.isPresent()) return new OrbitInput("PUBLIC_GP", gp.get().body());
    throw ApiException.missing("Orbit input not found");
  }

  @PostMapping({"/api/predictions", "/internal/predictions"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode predict(
      @RequestBody PredictionRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    var prior = store.replay("prediction:" + actor.getName(), key, request);
    if (prior.isPresent()) return prior.get();
    var initial = orbit(request.solutionId());
    var prediction = computation.predict(initial, request.horizon(), request.stepSeconds());
    var ground = prediction.samples().stream().map(references::groundPoint).toList();
    var document = new EphemerisDocument(prediction, ground, references.digest());
    objects.ensureBucket();
    String objectReference =
        objects.write(
            "application/json",
            new ByteArrayInputStream(json.write(document).getBytes(StandardCharsets.UTF_8)));
    // Content-addressed writes can safely leave an orphan after a database failure; history never
    // references an unfinished object.
    return store.idempotent(
        "prediction:" + actor.getName(),
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var manifest =
              new PredictionManifest(
                  id,
                  initial.solutionId(),
                  initial.spacecraftId(),
                  prediction.model(),
                  prediction.frame(),
                  request.horizon(),
                  request.stepSeconds(),
                  prediction.samples().size(),
                  references.digest(),
                  objectReference);
          var state = store.create("prediction", id, manifest);
          store.event(
              "OrbitPredictionCreated", id, state.version(), UUID.randomUUID(), null, manifest);
          return state;
        });
  }

  @GetMapping({"/api/predictions/{id}", "/internal/predictions/{id}"})
  public PredictionManifest prediction(@PathVariable String id) {
    return store.require("prediction", id, PredictionManifest.class).body();
  }

  @GetMapping({"/api/predictions/{id}/samples", "/internal/predictions/{id}/samples"})
  public org.springframework.http.ResponseEntity<
          org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      samples(@PathVariable String id) {
    var manifest = prediction(id);
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            out -> {
              try (var input = objects.read(manifest.objectReference())) {
                input.transferTo(out);
              }
            });
  }

  public record DesignationRequest(
      String solutionId, long expectedVersion, String decisionReference) {
    public DesignationRequest {
      msc.domain.shared.Checks.text(solutionId);
      msc.domain.shared.Checks.text(decisionReference);
      if (expectedVersion < 0)
        throw new IllegalArgumentException("Expected version must be nonnegative");
    }
  }

  @PostMapping("/api/orbit-designations/{spacecraftId}")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode designate(
      @PathVariable String spacecraftId,
      @RequestBody DesignationRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    var bound = Map.of("spacecraftId", spacecraftId, "request", request);
    return store.idempotent(
        "orbit-designation:" + actor.getName(),
        key,
        bound,
        () -> {
          var initial = orbit(request.solutionId());
          if (!initial.spacecraftId().equals(spacecraftId))
            throw ApiException.invalid("Orbit belongs to another spacecraft");
          var now = clock.now();
          computation.predict(
              initial, new TimeWindow(now, now.plus(new MissionDuration(1_000_000_000L))), 1);
          var owner = new msc.domain.shared.Ids.SpacecraftId(spacecraftId);
          var solutionId = new msc.domain.shared.Ids.OrbitSolutionId(initial.solutionId());
          var decision = actor.getName() + ":" + request.decisionReference();
          msc.domain.flightdynamics.OperationalDesignation selected;
          if (request.expectedVersion() == 0) {
            selected =
                new msc.domain.flightdynamics.OperationalDesignation(
                    owner, solutionId, 1, now, decision);
          } else {
            var previous =
                store.require(
                    "orbit-designation",
                    spacecraftId,
                    msc.domain.flightdynamics.OperationalDesignation.class);
            if (previous.version() != request.expectedVersion())
              throw ApiException.conflict("Designation version changed");
            selected = previous.body().select(solutionId, owner, now, decision);
          }
          var saved =
              request.expectedVersion() == 0
                  ? store.create("orbit-designation", spacecraftId, selected)
                  : store.update(
                      "orbit-designation", spacecraftId, request.expectedVersion(), selected);
          store.event(
              "OperationalOrbitDesignated",
              spacecraftId,
              saved.version(),
              UUID.randomUUID(),
              null,
              selected);
          return saved;
        });
  }

  @GetMapping({
    "/api/orbit-designations/{spacecraftId}",
    "/internal/orbit-designations/{spacecraftId}"
  })
  public msc.domain.flightdynamics.OperationalDesignation designation(
      @PathVariable String spacecraftId) {
    return store
        .require(
            "orbit-designation",
            spacecraftId,
            msc.domain.flightdynamics.OperationalDesignation.class)
        .body();
  }

  public record AccessRequest(
      String solutionId, msc.domain.flightdynamics.AccessPrediction.Query query) {
    public AccessRequest {
      msc.domain.shared.Checks.text(solutionId);
      Objects.requireNonNull(query);
    }
  }

  @PostMapping({"/api/access-predictions", "/internal/access-predictions"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode access(
      @RequestBody AccessRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "access:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    var result =
        new msc.orbit.OrekitAccessPredictor(references)
            .predict(orbit(request.solutionId()), request.query());
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          String id = UUID.randomUUID().toString();
          var saved = store.create("access-prediction", id, result);
          store.event(
              "GeometricAccessPredicted", id, saved.version(), UUID.randomUUID(), null, result);
          return saved;
        });
  }

  @GetMapping({"/api/access-predictions/{id}", "/internal/access-predictions/{id}"})
  public msc.domain.flightdynamics.AccessPrediction access(@PathVariable String id) {
    return store
        .require("access-prediction", id, msc.domain.flightdynamics.AccessPrediction.class)
        .body();
  }

  @GetMapping("/internal/reference-context")
  public OrekitReferenceFrames.Context referenceContext() {
    return references.describe();
  }

  public record UtcInput(String utc) {}

  @PostMapping("/internal/time/utc-to-tai")
  public MissionInstant time(@RequestBody UtcInput input) {
    return references.fromUtc(input.utc());
  }
}
