package msc.services.planning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.TaskingContracts.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.springframework.stereotype.Component;

/** Collects only actually available owner evidence; never fabricates a complete PlanningRun. */
@Component
public class PlanningInputs {
  public record Evidence(String service, String path, String sha256, JsonNode value) {}

  public record Asset(
      String spacecraftId,
      Map<Input, Evidence> inputs,
      List<String> missing,
      Optional<Evidence> catalog,
      Optional<Evidence> pointGeometry,
      Optional<Evidence> simulationModel,
      List<PlanningOptions.Option> activityOptions,
      Optional<PlanningOperations.Captured> operations) {
    public Asset {
      inputs = Map.copyOf(inputs);
      missing = List.copyOf(missing);
      catalog = catalog == null ? Optional.empty() : catalog;
      pointGeometry = pointGeometry == null ? Optional.empty() : pointGeometry;
      simulationModel = simulationModel == null ? Optional.empty() : simulationModel;
      activityOptions = activityOptions == null ? List.of() : List.copyOf(activityOptions);
      operations = operations == null ? Optional.empty() : operations;
    }

    public Asset(
        String spacecraftId,
        Map<Input, Evidence> inputs,
        List<String> missing,
        Optional<Evidence> catalog,
        Optional<Evidence> pointGeometry,
        Optional<Evidence> simulationModel,
        List<PlanningOptions.Option> activityOptions) {
      this(
          spacecraftId,
          inputs,
          missing,
          catalog,
          pointGeometry,
          simulationModel,
          activityOptions,
          Optional.empty());
    }

    public Asset(
        String spacecraftId,
        Map<Input, Evidence> inputs,
        List<String> missing,
        Optional<Evidence> catalog,
        Optional<Evidence> pointGeometry) {
      this(spacecraftId, inputs, missing, catalog, pointGeometry, Optional.empty(), List.of());
    }

    public Asset(String spacecraftId, Map<Input, Evidence> inputs, List<String> missing) {
      this(spacecraftId, inputs, missing, Optional.empty(), Optional.empty());
    }
  }

  public record Attempt(
      String id,
      String requestId,
      long revision,
      MissionInstant capturedAt,
      String status,
      Optional<Evidence> request,
      List<Asset> assets,
      List<String> issues) {}

  private final ServiceHttp http;
  private final Json json;
  private final msc.ports.Clock clock;
  private final PlanningGeometry geometrySearch;

  public PlanningInputs(ServiceHttp http, Json json, msc.ports.Clock clock, StateStore store) {
    this.http = http;
    this.json = json;
    this.clock = clock;
    this.geometrySearch = new PlanningGeometry(http, json, store);
  }

  static String segment(String id) {
    return java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  public RequestDetails request(String id) {
    return http.get("tasking", "/internal/requests/" + segment(id), RequestDetails.class);
  }

  private Evidence get(String service, String path) {
    var value = http.get(service, path, JsonNode.class);
    if (value == null || !value.isObject())
      throw ApiException.invalid("Expected an object from " + service + path);
    return new Evidence(service, path, json.fingerprint(value), value);
  }

  private Evidence capture(String service, String path, Object value) {
    return new Evidence(service, path, json.fingerprint(value), json.tree(value));
  }

  public Attempt collect(PlanningIntake.Claim claim) {
    String id = UUID.randomUUID().toString();
    var at = clock.now();
    var issues = new ArrayList<String>();
    var assets = new ArrayList<Asset>();
    RequestDetails live;
    try {
      live = request(claim.requestId());
    } catch (RuntimeException unavailable) {
      return new Attempt(
          id,
          claim.requestId(),
          claim.revision(),
          at,
          "REQUEST_UNAVAILABLE",
          Optional.empty(),
          List.of(),
          List.of("TASKING_UNAVAILABLE"));
    }
    var request =
        Optional.of(capture("tasking", "/internal/requests/" + segment(claim.requestId()), live));
    if (live.request().revision() != claim.revision() || live.area().isEmpty())
      return new Attempt(
          id,
          claim.requestId(),
          claim.revision(),
          at,
          "INVALIDATED",
          request,
          List.of(),
          List.of("REQUEST_REVISION_CHANGED"));
    if (live.request().terminal()
        || live.request().deadline().filter(d -> clock.now().compareTo(d) >= 0).isPresent())
      return new Attempt(
          id,
          claim.requestId(),
          claim.revision(),
          at,
          "TERMINAL",
          request,
          List.of(),
          List.of("REQUEST_TERMINAL_OR_EXPIRED"));
    if (live.request().status() == msc.domain.tasking.ObservationRequest.Status.SCHEDULED)
      return new Attempt(
          id,
          claim.requestId(),
          claim.revision(),
          at,
          "WAITING_INPUTS",
          request,
          List.of(),
          List.of("TASKING_REPORTS_SCHEDULED", "SCHEDULE_RECONCILIATION_REQUIRED"));
    Evidence references = null;
    try {
      references = get("flight-dynamics", "/internal/reference-context");
    } catch (RuntimeException unavailable) {
      issues.add("TIME_FRAME_REFERENCES_UNAVAILABLE");
    }
    JsonNode missions;
    try {
      missions = http.get("mission-definition", "/internal/missions", JsonNode.class);
      if (!missions.isArray()) throw ApiException.invalid("Invalid mission list");
    } catch (RuntimeException unavailable) {
      return new Attempt(
          id,
          claim.requestId(),
          claim.revision(),
          at,
          "WAITING_INPUTS",
          request,
          List.of(),
          List.of("MISSION_DEFINITIONS_UNAVAILABLE"));
    }
    if (missions.isEmpty()) issues.add("NO_CONFIGURED_MISSION");
    var horizonEnd = at.plus(new msc.domain.time.MissionDuration(86_400_000_000_000L));
    if (live.request().deadline().isPresent()
        && live.request().deadline().get().compareTo(horizonEnd) < 0)
      horizonEnd = live.request().deadline().get();
    var horizon = new msc.domain.time.TimeWindow(at, horizonEnd);
    Evidence weather = null;
    try {
      var query = new msc.contracts.WeatherContracts.Query(live.area().orElseThrow(), horizon);
      String path = "/internal/weather/query";
      var result =
          http.post("reference-data", path, query, UUID.randomUUID().toString(), JsonNode.class);
      var forecast =
          json.convert(result.get("body"), msc.contracts.WeatherContracts.Forecast.class);
      if (!forecast.id().equals(result.path("id").asText())
          || !forecast.covers(query)
          || forecast.issuedAt().compareTo(at) > 0)
        throw ApiException.invalid("Weather source does not cover the planning query");
      weather = capture("reference-data", path, Map.of("query", query, "snapshot", result));
    } catch (RuntimeException unavailable) {
      issues.add("WEATHER_FOR_AREA_AND_HORIZON_UNAVAILABLE");
    }
    Evidence ground = null;
    try {
      var query = new msc.contracts.GroundAvailabilityContracts.Query(horizon);
      String path = "/internal/ground-availability";
      var result =
          http.post("ground-operations", path, query, UUID.randomUUID().toString(), JsonNode.class);
      var snapshot =
          json.convert(
              result.get("body"), msc.contracts.GroundAvailabilityContracts.Snapshot.class);
      if (!snapshot.id().equals(result.path("id").asText())
          || !snapshot.query().equals(query)
          || !"LOCAL_ALLOCATIONS_REQUIRE_PROVIDER_CONFIRMATION".equals(snapshot.scope()))
        throw ApiException.invalid("Ground availability query binding mismatch");
      ground = capture("ground-operations", path, result);
    } catch (RuntimeException unavailable) {
      issues.add("GROUND_AVAILABILITY_UNAVAILABLE");
    }
    if (missions.size() > 32) issues.add("FLEET_EXCEEDS_SINGLE_ATTEMPT_LIMIT");
    long budget = System.nanoTime() + 20_000_000_000L;
    var geometryBudget = new PlanningGeometry.Budget(2);
    var orderedMissions = new ArrayList<JsonNode>();
    missions.forEach(orderedMissions::add);
    orderedMissions.sort(Comparator.comparing(e -> e.path("body").path("spacecraftId").asText()));
    if (!orderedMissions.isEmpty())
      Collections.rotate(
          orderedMissions, -(int) Math.floorMod(claim.attemptNumber(), orderedMissions.size()));
    for (var entry : orderedMissions) {
      if (assets.size() >= 32 || System.nanoTime() > budget) {
        issues.add("INPUT_COLLECTION_BUDGET_REACHED");
        break;
      }
      var inputs = new EnumMap<Input, Evidence>(Input.class);
      var missing = new ArrayList<String>();
      if (ground == null) missing.add("GROUND_SCHEDULE");
      else {
        inputs.put(Input.GROUND_SCHEDULE, ground);
        var stations = ground.value().path("body").path("stations");
        if (stations.isEmpty()) missing.add("NO_CONFIGURED_STATION");
        else {
          boolean free = false;
          for (var station : stations) if (!station.path("free").isEmpty()) free = true;
          if (!free) missing.add("NO_UNALLOCATED_GROUND_TIME");
        }
      }
      if (weather == null) missing.add("WEATHER");
      else {
        inputs.put(Input.WEATHER, weather);
        if (weather.value().path("snapshot").path("body").path("cloudFraction").asDouble()
            > live.criteria().maximumCloudFraction()) missing.add("CLOUD_CRITERION_NOT_MET");
      }
      var profile = json.convert(entry.get("body"), MissionProfile.class);
      String craft = profile.spacecraftId();
      inputs.put(
          Input.MISSION_DEFINITION,
          capture("mission-definition", "/internal/missions/" + segment(craft), entry));
      if (references != null) {
        inputs.put(Input.EOP, references);
        inputs.put(Input.LEAP_SECONDS, references);
      } else {
        missing.add("EOP");
        missing.add("LEAP_SECONDS");
      }
      Evidence catalog = null;
      Evidence simulationModel = null;
      try {
        simulationModel =
            get("mission-definition", "/internal/simulation-planning-models/" + segment(craft));
        var model =
            json.convert(
                simulationModel.value().get("body"),
                msc.contracts.SimulationPlanningContracts.Model.class);
        if (!craft.equals(model.spacecraftId())
            || !profile.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
          throw ApiException.invalid("Simulation planning model mission binding mismatch");
      } catch (RuntimeException unavailable) {
        simulationModel = null;
        missing.add("SIMULATION_PLANNING_MODEL");
      }
      try {
        var agility = get("mission-definition", "/internal/agility-models/" + segment(craft));
        var model =
            json.convert(agility.value().get("body"), msc.contracts.AgilityContracts.Model.class);
        if (!craft.equals(model.spacecraftId())
            || !profile.missionDefinitionVersion().equals(model.missionDefinitionVersion()))
          throw ApiException.invalid("Agility model mission binding mismatch");
        inputs.put(Input.AGILITY, agility);
      } catch (RuntimeException unavailable) {
        missing.add("AGILITY");
      }
      try {
        catalog =
            get(
                "mission-definition",
                "/internal/catalog/"
                    + segment(profile.catalogId())
                    + "/versions/"
                    + profile.catalogVersion());
      } catch (RuntimeException unavailable) {
        missing.add("APPROVED_ACTIVITY_CATALOG");
      }
      PlanningOperations.Captured operations = null;
      if (catalog != null && System.nanoTime() <= budget) {
        try {
          operations = new PlanningOperations(http, json).collect(profile, catalog);
        } catch (RuntimeException unavailable) {
          missing.add("APPROVED_OPERATION_INPUTS");
        }
      } else missing.add("APPROVED_OPERATION_INPUTS");
      try {
        var pointer = get("flight-dynamics", "/internal/orbit-designations/" + segment(craft));
        String solution = pointer.value().get("orbitSolutionId").get("value").asText();
        var orbit = get("flight-dynamics", "/internal/orbit-inputs/" + segment(solution));
        inputs.put(
            Input.ORBIT,
            capture(
                "flight-dynamics",
                pointer.path(),
                Map.of("designation", pointer, "solution", orbit)));
      } catch (RuntimeException unavailable) {
        missing.add("DESIGNATED_ORBIT");
      }
      try {
        var state = get("monitoring", "/internal/spacecraft-estimates/" + segment(craft));
        var estimate = json.convert(state.value().get("estimate").get("body"), Estimate.class);
        if (!estimate.binding().spacecraftId().equals(craft))
          throw ApiException.invalid("Monitoring owner mismatch");
        inputs.put(Input.SPACECRAFT_STATE, state);
        if (estimate.binding().environment() != Environment.SIMULATION
            && (inputs.containsKey(Input.AGILITY) || inputs.containsKey(Input.WEATHER)))
          missing.add("SIMULATION_INPUTS_IN_HARDWARE_CONTEXT");
        if (estimate.confidence(clock.now()) != Confidence.FRESH)
          missing.add("FRESH_SPACECRAFT_STATE");
        try {
          var model = get("mission-definition", "/internal/propellant-models/" + segment(craft));
          var definition =
              json.convert(
                  model.value().get("body"), msc.contracts.PropellantContracts.Model.class);
          if (!profile.missionDefinitionVersion().equals(definition.missionDefinitionVersion()))
            throw ApiException.invalid("Propellant model mission binding mismatch");
          long telemetryVersion = state.value().path("estimate").path("version").asLong();
          long modelVersion = model.value().path("version").asLong();
          var query =
              new msc.contracts.PropellantContracts.Query(craft, telemetryVersion, modelVersion);
          String path = "/internal/propellant-estimates";
          var result =
              http.post(
                  "flight-dynamics", path, query, UUID.randomUUID().toString(), JsonNode.class);
          var propellant =
              json.convert(result.get("body"), msc.contracts.PropellantContracts.Snapshot.class);
          if (!propellant.id().equals(result.path("id").asText())
              || !propellant.estimate().spacecraftId().value().equals(craft)
              || propellant.telemetryVersion() != telemetryVersion
              || propellant.modelVersion() != modelVersion
              || !propellant.source().equals(estimate)
              || !propellant.model().equals(definition))
            throw ApiException.invalid("Propellant source binding mismatch");
          propellant.lowerBoundAt(horizon.start());
          propellant.lowerBoundAt(horizon.end());
          if (propellant.source().confidence(clock.now()) != Confidence.FRESH)
            throw ApiException.invalid("Propellant source aged during collection");
          inputs.put(Input.PROPELLANT, capture("flight-dynamics", path, result));
        } catch (RuntimeException unavailable) {
          missing.add("PROPELLANT");
        }
      } catch (RuntimeException unavailable) {
        missing.add("SPACECRAFT_STATE");
        missing.add("PROPELLANT");
      }
      try {
        String path = "/internal/safety/" + segment(craft) + "/check";
        var decision =
            http.post("anomaly", path, Map.of(), UUID.randomUUID().toString(), JsonNode.class);
        inputs.put(
            Input.POLICY,
            capture(
                "anomaly",
                path,
                Map.of(
                    "safety",
                    decision,
                    "activityAuthority",
                    catalog == null ? json.tree(null) : catalog)));
        if (!decision.path("clear").asBoolean(false)) missing.add("SAFETY_CLEARANCE");
      } catch (RuntimeException unavailable) {
        missing.add("SAFETY_POLICY");
      }
      Evidence geometry = null;
      if (catalog != null
          && inputs.containsKey(Input.ORBIT)
          && inputs.containsKey(Input.AGILITY)
          && references != null) {
        if (System.nanoTime() > budget) {
          missing.add("POINT_GEOMETRY_BUDGET_REACHED");
        } else {
          try {
            geometry =
                geometrySearch.search(
                    craft,
                    live.area().orElseThrow(),
                    horizon,
                    inputs,
                    catalog,
                    Optional.ofNullable(simulationModel),
                    geometryBudget);
          } catch (PlanningGeometry.BudgetReached exhausted) {
            missing.add("POINT_GEOMETRY_BUDGET_REACHED");
          } catch (RuntimeException unavailable) {
            missing.add("POINT_GEOMETRY_UNAVAILABLE");
          }
        }
      } else missing.add("POINT_GEOMETRY_INPUTS_UNAVAILABLE");
      List<PlanningOptions.Option> options = List.of();
      if (geometry != null && catalog != null && simulationModel != null) {
        try {
          var now = clock.now();
          if (now.compareTo(horizon.end()) < 0) {
            options =
                PlanningOptions.derive(
                    json.convert(
                        geometry.value().path("prediction").path("body"),
                        msc.domain.flightdynamics.AccessPrediction.class),
                    json.convert(catalog.value(), CatalogEntry.class),
                    json.convert(
                        simulationModel.value().get("body"),
                        msc.contracts.SimulationPlanningContracts.Model.class),
                    new msc.domain.time.TimeWindow(now, horizon.end()));
          }
        } catch (IllegalArgumentException unavailable) {
          missing.add("APPROVED_ACTIVITY_OPTIONS");
        }
      }
      assets.add(
          new Asset(
              craft,
              inputs,
              missing,
              Optional.ofNullable(catalog),
              Optional.ofNullable(geometry),
              Optional.ofNullable(simulationModel),
              options,
              Optional.ofNullable(operations)));
    }
    return new Attempt(
        id,
        claim.requestId(),
        claim.revision(),
        at,
        "WAITING_INPUTS",
        request,
        List.copyOf(assets),
        List.copyOf(issues));
  }
}
