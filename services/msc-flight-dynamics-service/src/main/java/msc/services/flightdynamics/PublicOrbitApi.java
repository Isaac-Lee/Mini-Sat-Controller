package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.time.*;
import msc.orbit.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicOrbitApi {
  public record ImportRequest(String snapshotId) {
    public ImportRequest {
      if (snapshotId == null || !snapshotId.matches("gp-[0-9]{1,9}-[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid GP snapshot ID");
    }
  }

  public record Query(TimeWindow horizon, int stepSeconds) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final ObjectStorage objects;
  private final Json json;
  private final OrekitReferenceFrames references;
  private final GeneralPerturbationsAdapter propagation;
  private final msc.ports.Clock clock;

  public PublicOrbitApi(
      StateStore store,
      ServiceHttp http,
      ObjectStorage objects,
      Json json,
      OrekitReferenceFrames references,
      msc.ports.Clock clock) {
    this.store = store;
    this.http = http;
    this.objects = objects;
    this.json = json;
    this.references = references;
    this.clock = clock;
    this.propagation = new GeneralPerturbationsAdapter(references);
  }

  @PostMapping({"/api/public-orbits/import", "/internal/public-orbits/import"})
  @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
  public JsonNode ingest(
      @RequestBody ImportRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "public-orbit-import:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    var snapshot =
        http.get(
            "reference-data", "/internal/orbit-references/" + request.snapshotId(), Snapshot.class);
    if (!snapshot.id().equals(request.snapshotId()))
      throw ApiException.invalid("Reference snapshot binding mismatch");
    var epoch = propagation.epoch(snapshot.elements());
    propagation.predict(
        snapshot.id(),
        snapshot.elements(),
        new TimeWindow(epoch, epoch.plus(new MissionDuration(1_000_000_000L))),
        1);
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          store.lock("orbit-solution:" + snapshot.id());
          if (store.find("orbit", snapshot.id(), JsonNode.class).isPresent())
            throw ApiException.conflict("Orbit solution ID already belongs to a Cartesian input");
          return store
              .find("public-orbit", snapshot.id(), Snapshot.class)
              .orElseGet(
                  () -> {
                    var saved = store.create("public-orbit", snapshot.id(), snapshot);
                    store.event(
                        "PublicOrbitImported",
                        saved.id(),
                        saved.version(),
                        UUID.randomUUID(),
                        null,
                        snapshot);
                    return saved;
                  });
        });
  }

  @PostMapping("/api/public-orbit-designations/{spacecraftId}")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode designate(
      @PathVariable String spacecraftId,
      @RequestBody OrbitApi.DesignationRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    var bound = Map.of("spacecraftId", spacecraftId, "request", request);
    return store.idempotent(
        "public-orbit-designation:" + actor.getName(),
        key,
        bound,
        () -> {
          store.lock("orbit-designation:" + spacecraftId);
          var selected = orbit(request.solutionId());
          if (!spacecraftId.equals("norad-" + selected.elements().noradId()))
            throw ApiException.invalid("Public orbit belongs to another spacecraft");
          var now = clock.now();
          propagation.predict(
              selected.id(),
              selected.elements(),
              new TimeWindow(now, now.plus(new MissionDuration(1_000_000_000L))),
              1);
          var owner = new msc.domain.shared.Ids.SpacecraftId(spacecraftId);
          var id = new msc.domain.shared.Ids.OrbitSolutionId(selected.id());
          var old =
              store.find(
                  "orbit-designation",
                  spacecraftId,
                  msc.domain.flightdynamics.OperationalDesignation.class);
          if (request.expectedVersion() != (old.isEmpty() ? 0 : old.get().version()))
            throw ApiException.conflict("Designation version changed");
          var pointer =
              old.isEmpty()
                  ? new msc.domain.flightdynamics.OperationalDesignation(
                      owner, id, 1, now, actor.getName() + ":" + request.decisionReference())
                  : old.get()
                      .body()
                      .select(id, owner, now, actor.getName() + ":" + request.decisionReference());
          var saved =
              old.isEmpty()
                  ? store.create("orbit-designation", spacecraftId, pointer)
                  : store.update("orbit-designation", spacecraftId, old.get().version(), pointer);
          store.event(
              "OperationalOrbitDesignated",
              spacecraftId,
              saved.version(),
              UUID.randomUUID(),
              null,
              pointer);
          return saved;
        });
  }

  @GetMapping({"/api/public-orbits/{id}", "/internal/public-orbits/{id}"})
  public Snapshot orbit(@PathVariable String id) {
    return store.require("public-orbit", id, Snapshot.class).body();
  }

  @PostMapping({"/api/public-orbits/{id}/predictions", "/internal/public-orbits/{id}/predictions"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode predict(
      @PathVariable String id,
      @RequestBody Query query,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    String scope = "public-prediction:" + actor.getName();
    var bound = Map.of("id", id, "query", query);
    var prior = store.replay(scope, key, bound);
    if (prior.isPresent()) return prior.get();
    var source = orbit(id);
    var prediction =
        propagation.predict(id, source.elements(), query.horizon(), query.stepSeconds());
    var ground = prediction.samples().stream().map(references::groundPoint).toList();
    var document = new OrbitApi.EphemerisDocument(prediction, ground, references.digest());
    objects.ensureBucket();
    var objectRef =
        objects.write(
            "application/json",
            new ByteArrayInputStream(json.write(document).getBytes(StandardCharsets.UTF_8)));
    return store.idempotent(
        scope,
        key,
        bound,
        () -> {
          String predictionId = UUID.randomUUID().toString();
          var manifest =
              new OrbitApi.PredictionManifest(
                  predictionId,
                  id,
                  "norad-" + source.elements().noradId(),
                  prediction.model(),
                  prediction.frame(),
                  query.horizon(),
                  query.stepSeconds(),
                  prediction.samples().size(),
                  references.digest(),
                  objectRef);
          var saved = store.create("prediction", predictionId, manifest);
          store.event(
              "OrbitPredictionCreated",
              predictionId,
              saved.version(),
              UUID.randomUUID(),
              null,
              manifest);
          return saved;
        });
  }

  @PostMapping({
    "/api/public-orbits/{id}/access-predictions",
    "/internal/public-orbits/{id}/access-predictions"
  })
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode access(
      @PathVariable String id,
      @RequestBody AccessPrediction.Query query,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    String scope = "public-access:" + actor.getName();
    var bound = Map.of("id", id, "query", query);
    var prior = store.replay(scope, key, bound);
    if (prior.isPresent()) return prior.get();
    var source = orbit(id);
    var result =
        propagation.access(id, "norad-" + source.elements().noradId(), source.elements(), query);
    return store.idempotent(
        scope,
        key,
        bound,
        () -> {
          String predictionId = UUID.randomUUID().toString();
          var saved = store.create("access-prediction", predictionId, result);
          store.event(
              "GeometricAccessPredicted",
              predictionId,
              saved.version(),
              UUID.randomUUID(),
              null,
              result);
          return saved;
        });
  }
}
