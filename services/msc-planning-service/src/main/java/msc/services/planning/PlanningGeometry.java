package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.AgilityContracts;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.TaskingContracts.Area;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.time.MissionDuration;
import msc.domain.time.TimeWindow;
import msc.platform.*;
import msc.services.planning.PlanningInputs.Evidence;

/**
 * Owner-computed point access, deliberately separate from full observation feasibility.
 *
 * <p>{@link #bind} performs no I/O: it derives the exact FD query and the source ids/versions that
 * identify it, so a caller can decide whether to reuse a previously stored search before ever
 * calling {@link #search}, which performs the single FD network call.
 */
final class PlanningGeometry {
  static final class BudgetReached extends RuntimeException {}

  static final class Budget {
    private int remaining;

    Budget(int calls) {
      if (calls < 0) throw new IllegalArgumentException("Negative geometry budget");
      remaining = calls;
    }

    private void take() {
      if (remaining == 0) throw new BudgetReached();
      remaining--;
    }
  }

  private final ServiceHttp http;
  private final Json json;
  private final StateStore store;

  PlanningGeometry(ServiceHttp http, Json json, StateStore store) {
    this.http = http;
    this.json = json;
    this.store = store;
  }

  Evidence search(
      String craft, Area area, TimeWindow horizon, Map<Input, Evidence> inputs, Evidence catalog) {
    return search(craft, area, horizon, inputs, catalog, Optional.empty());
  }

  Evidence search(
      String craft,
      Area area,
      TimeWindow horizon,
      Map<Input, Evidence> inputs,
      Evidence catalog,
      Optional<Evidence> simulationModel) {
    return search(craft, area, horizon, inputs, catalog, simulationModel, new Budget(1));
  }

  Evidence search(
      String craft,
      Area area,
      TimeWindow horizon,
      Map<Input, Evidence> inputs,
      Evidence catalog,
      Optional<Evidence> simulationModel,
      Budget budget) {
    double sensorLimit = 60;
    if (simulationModel.isPresent()) {
      var model =
          json.convert(
              simulationModel.get().value().get("body"),
              msc.contracts.SimulationPlanningContracts.Model.class);
      if (!craft.equals(model.spacecraftId()))
        throw ApiException.invalid("Sensor spacecraft mismatch");
      sensorLimit = model.maximumOffNadirDegrees();
    }
    var start =
        msc.domain.time.MissionInstant.tai(Math.floorDiv(horizon.start().seconds(), 300) * 300);
    var end = start.plus(new MissionDuration(86_400_000_000_000L));
    if (horizon.end().compareTo(end) < 0) end = horizon.end();
    var binding = bind(craft, area, new TimeWindow(start, end), inputs, catalog, sensorLimit);
    String key =
        json.fingerprint(
            Map.of(
                "binding",
                binding,
                "area",
                area,
                "catalog",
                catalog.sha256(),
                "agility",
                inputs.get(Input.AGILITY).sha256(),
                "simulationModel",
                simulationModel.map(Evidence::sha256).orElse("absent")));
    var cached = store.find("planning-geometry", key, Evidence.class);
    if (cached.isPresent()) return cached.get().body();
    budget.take();
    var result = search(binding, area).evidence();
    var value = result.value().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("searchKey", key);
    return new Evidence(result.service(), result.path(), json.fingerprint(value), value);
  }

  /**
   * Every field FD-side geometric visibility actually depends on. {@code orbitEvidenceSha256} and
   * {@code agilityModelVersion}/{@code catalogVersion} are included (not just their subject ids) so
   * that a source *content* change invalidates reuse even when the id is unchanged.
   */
  record Binding(
      String spacecraftId,
      String orbitSolutionId,
      String orbitKind,
      String orbitEvidenceSha256,
      long agilityModelVersion,
      String catalogId,
      long catalogVersion,
      String eopDigest,
      AccessPrediction.Query query) {}

  record SearchResult(Evidence evidence, AccessPrediction prediction) {}

  /**
   * Pure derivation of the FD query and its binding from already-collected evidence. {@code
   * searchHorizon} must already be the quantised search bucket; unlike the input-collection
   * horizon, it is never {@code clock.now()} directly.
   */
  Binding bind(
      String craft,
      Area area,
      TimeWindow searchHorizon,
      Map<Input, Evidence> inputs,
      Evidence catalog,
      double sensorLimit) {
    if (!inputs.containsKey(Input.ORBIT)
        || !inputs.containsKey(Input.AGILITY)
        || !inputs.containsKey(Input.EOP)) throw ApiException.invalid("Missing geometry inputs");
    var activity = json.convert(catalog.value(), CatalogEntry.class);
    var agility =
        json.convert(inputs.get(Input.AGILITY).value().get("body"), AgilityContracts.Model.class);
    var orbit = inputs.get(Input.ORBIT).value();
    String solution =
        orbit.path("designation").path("value").path("orbitSolutionId").path("value").asText();
    if (solution.isBlank() || !craft.equals(agility.spacecraftId()))
      throw ApiException.invalid("Geometry input binding mismatch");
    // Upstream invariant, not checked again here: OrbitApi predicts CARTESIAN solutions from
    // InitialState.spacecraftId(); PublicOrbitApi.designate refuses any designation where
    // spacecraftId != "norad-" + selected.elements().noradId(), so PUBLIC_GP predictions always
    // carry spacecraftId == craft too. search() below re-asserts this on the FD response.
    String kind = orbit.path("solution").path("value").path("kind").asText();
    String digest = inputs.get(Input.EOP).value().path("digest").asText();
    if (digest.isBlank()) throw ApiException.invalid("Missing EOP digest for geometry binding");
    var query =
        new AccessPrediction.Query(
            AccessPrediction.Kind.POINT_IMAGING,
            new AccessPrediction.Target(
                area.id() + ":center",
                (area.south() + area.north()) / 2,
                (area.west() + area.east()) / 2,
                0),
            searchHorizon,
            0,
            Math.min(agility.maximumOffNadirDegrees(), sensorLimit),
            (int) Math.ceil(activity.durationSeconds()));
    return new Binding(
        craft,
        solution,
        kind,
        inputs.get(Input.ORBIT).sha256(),
        inputs.get(Input.AGILITY).value().path("version").asLong(),
        catalog.value().path("id").asText(),
        catalog.value().path("version").asLong(),
        digest,
        query);
  }

  /** The single FD network call this stage ever makes; callers decide first whether to reuse. */
  SearchResult search(Binding binding, Area area) {
    String path;
    Object body;
    switch (binding.orbitKind()) {
      case "CARTESIAN" -> {
        path = "/internal/access-predictions";
        body = Map.of("solutionId", binding.orbitSolutionId(), "query", binding.query());
      }
      case "PUBLIC_GP" -> {
        path =
            "/internal/public-orbits/"
                + PlanningInputs.segment(binding.orbitSolutionId())
                + "/access-predictions";
        body = binding.query();
      }
      default -> throw ApiException.invalid("Unsupported orbit source kind");
    }
    var result =
        http.post(
            "flight-dynamics",
            path,
            body,
            "planning-geometry-" + json.fingerprint(binding),
            JsonNode.class);
    var prediction = json.convert(result.get("body"), AccessPrediction.class);
    if (result.path("id").asText().isBlank()
        || result.path("version").asLong() < 1
        || !binding.orbitSolutionId().equals(prediction.solutionId())
        || !binding.spacecraftId().equals(prediction.spacecraftId())
        || !binding.query().equals(prediction.query())
        || binding.eopDigest().isBlank()
        || !binding.eopDigest().equals(prediction.referenceDigest()))
      throw ApiException.invalid("Geometry prediction binding mismatch");
    var minimum =
        new MissionDuration((long) binding.query().minimumDurationSeconds() * 1_000_000_000L);
    TimeWindow previous = null;
    for (var window : prediction.windows()) {
      if (!binding.query().horizon().contains(window)
          || window.start().plus(minimum).compareTo(window.end()) > 0
          || (previous != null && previous.end().compareTo(window.start()) > 0))
        throw ApiException.invalid("Invalid geometry access windows");
      previous = window;
    }
    var value =
        json.tree(
            Map.of(
                "scope",
                "POINT_GEOMETRY_ONLY",
                "feasibility",
                "NOT_EVALUATED",
                "area",
                area,
                "prediction",
                result));
    var evidence = new Evidence("flight-dynamics", path, json.fingerprint(value), value);
    return new SearchResult(evidence, prediction);
  }
}
