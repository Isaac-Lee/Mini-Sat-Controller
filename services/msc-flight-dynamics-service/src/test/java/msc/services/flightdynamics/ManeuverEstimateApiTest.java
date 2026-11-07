package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.contracts.AgilityContracts;
import msc.contracts.ManeuverContracts.*;
import msc.contracts.PointingContracts.*;
import msc.domain.flightdynamics.AccessPrediction;
import msc.domain.flightdynamics.RestToRestSlew.Shape;
import msc.domain.flightdynamics.Trajectory;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ManeuverEstimateApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  static final String AGILITY_PATH = "/internal/agility-models/sim-test/versions/3";
  // 30 deg off-nadir limit, 2 deg/s, 5 s settling; with 0.5 deg/s² the profile turns
  // trapezoidal above 8 deg.
  static final AgilityContracts.Model AGILITY =
      new AgilityContracts.Model("sim-test", "sim-v1", "SIMULATION", 30, 2, 5, "synthetic-test");
  static final AccessPrediction.Target TARGET =
      new AccessPrediction.Target("aoi-1", 37.5, 127.0, 0);

  StateStore store;
  Json json;
  ServiceHttp http;
  ManeuverEstimateApi api;
  final org.springframework.security.core.Authentication actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    new JdbcTemplate(ds).execute("TRUNCATE state_head,state_history,outbox");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    store =
        new StateStore(
            new JdbcTemplate(ds),
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(0));
    http = mock(ServiceHttp.class);
    api = new ManeuverEstimateApi(store, http, json);
    agility(3, AGILITY);
    store.transaction(
        () -> {
          store.create(
              "required-target-pointing",
              "rtp-1",
              pointing(
                  "sim-test",
                  List.of(
                      sample(1000, 0, 10, true),
                      sample(1010, 2, 10, true),
                      sample(1020, 22, 10, true),
                      sample(1100, 22, 40, true),
                      sample(1200, 22, 10, false))));
          store.create(
              "required-target-pointing",
              "rtp-nanos",
              pointing(
                  "sim-test",
                  "solution-1",
                  List.of(
                      sample(MissionInstant.tai(2000), 0, 10, true),
                      sample(new MissionInstant(2010, 500_000_000, TimeScale.TAI), 180, 10, true))));
          store.create(
              "required-target-pointing",
              "rtp-solution-2",
              pointing("sim-test", "solution-2", List.of(sample(1010, 0, 10, true))));
          return store.create(
              "required-target-pointing",
              "rtp-other",
              pointing("other-craft", List.of(sample(1010, 0, 10, true))));
        });
  }

  void agility(long version, AgilityContracts.Model model) {
    when(http.get("mission-definition", AGILITY_PATH, JsonNode.class))
        .thenReturn(json.tree(new StateStore.State<>("sim-test", version, model)));
  }

  static Request request(String pointingId, long... instants) {
    return new Request(
        "sim-test",
        3,
        0.5,
        Arrays.stream(instants)
            .mapToObj(t -> new PointingTarget(pointingId, MissionInstant.tai(t)))
            .toList());
  }

  @Test
  void flagsEachTransitionAndPersistsTheKinematicPlan() {
    var saved = api.estimate(request("rtp-1", 1000, 1010, 1020, 1100, 1200), "one", actor);
    var plan = json.convert(saved.get("body"), Plan.class);
    assertEquals(Scope.KINEMATIC_MODEL_NO_DYNAMICS, plan.scope());
    assertEquals("SIMULATION", plan.environment());
    assertEquals("EME2000_LINE_OF_SIGHT", plan.frame());
    assertEquals(AGILITY, plan.agility());
    assertEquals("CALLER_SIMULATION_ASSUMPTION", plan.maximumAccelerationSource());
    assertFalse(plan.feasible());
    var t = plan.transitions();
    assertEquals(4, t.size());

    // 2 deg, triangular: 2*sqrt(2/0.5) = 4 s + 5 s settling fits in 10 s.
    assertEquals(Shape.TRIANGULAR, t.get(0).shape());
    assertEquals(2, t.get(0).slewAngleDegrees(), 1e-9);
    assertEquals(9, t.get(0).requiredSeconds(), 1e-9);
    assertEquals(10, t.get(0).availableSeconds());
    assertTrue(t.get(0).feasible());

    // 20 deg, trapezoidal: 20/2 + 2/0.5 = 14 s + 5 s settling exceeds 10 s.
    assertEquals(Shape.TRAPEZOIDAL, t.get(1).shape());
    assertEquals(2, t.get(1).peakRateDegreesPerSecond());
    assertEquals(19, t.get(1).requiredSeconds(), 1e-9);
    assertEquals(Set.of(Infeasibility.INSUFFICIENT_TIME), t.get(1).infeasibilities());

    // No slew, but the destination exceeds the 30 deg off-nadir limit.
    assertEquals(Shape.NONE, t.get(2).shape());
    assertEquals(0, t.get(2).requiredSeconds());
    assertEquals(Set.of(Infeasibility.OFF_NADIR_LIMIT_EXCEEDED), t.get(2).infeasibilities());

    assertEquals(
        Set.of(Infeasibility.OFF_NADIR_LIMIT_EXCEEDED, Infeasibility.TARGET_NOT_VISIBLE),
        t.get(3).infeasibilities());

    assertEquals(plan, api.get(saved.get("id").asText()));
    assertEquals(
        json.fingerprint(saved),
        json.fingerprint(
            api.estimate(request("rtp-1", 1000, 1010, 1020, 1100, 1200), "one", actor)));
  }

  @Test
  void antiparallelSlewKeepsSubSecondAvailableTime() {
    var request =
        new Request(
            "sim-test",
            3,
            0.5,
            List.of(
                new PointingTarget("rtp-nanos", MissionInstant.tai(2000)),
                new PointingTarget(
                    "rtp-nanos", new MissionInstant(2010, 500_000_000, TimeScale.TAI))));
    var t = json.convert(api.estimate(request, "nanos", actor).get("body"), Plan.class);
    var transition = t.transitions().get(0);
    assertEquals(10.5, transition.availableSeconds());
    assertEquals(180, transition.slewAngleDegrees(), 1e-9);
    // 180/2 + 2/0.5 = 94 s + 5 s settling.
    assertEquals(99, transition.requiredSeconds(), 1e-9);
    assertEquals(Set.of(Infeasibility.INSUFFICIENT_TIME), transition.infeasibilities());
  }

  @Test
  void isDeterministicAcrossIdempotencyKeys() {
    var first = api.estimate(request("rtp-1", 1000, 1010, 1020), "a", actor);
    var second = api.estimate(request("rtp-1", 1000, 1010, 1020), "b", actor);
    assertEquals(
        json.fingerprint(first.get("body")), json.fingerprint(second.get("body")));
  }

  @Test
  void rejectsUnboundInputsWithoutStoring() {
    assertThrows(ApiException.class, () -> api.estimate(request("rtp-1", 1000, 1005), "gap", actor));
    assertThrows(
        ApiException.class, () -> api.estimate(request("missing", 1000, 1010), "missing", actor));
    assertThrows(
        ApiException.class,
        () ->
            api.estimate(
                new Request(
                    "sim-test",
                    3,
                    0.5,
                    List.of(
                        new PointingTarget("rtp-1", MissionInstant.tai(1000)),
                        new PointingTarget("rtp-other", MissionInstant.tai(1010)))),
                "other",
                actor));
    assertThrows(
        ApiException.class,
        () ->
            api.estimate(
                new Request(
                    "sim-test",
                    3,
                    0.5,
                    List.of(
                        new PointingTarget("rtp-1", MissionInstant.tai(1000)),
                        new PointingTarget("rtp-solution-2", MissionInstant.tai(1010)))),
                "mixed-solution",
                actor));
    when(http.get("mission-definition", AGILITY_PATH, JsonNode.class))
        .thenReturn(json.tree(Map.of("version", 3)));
    assertThrows(
        ApiException.class, () -> api.estimate(request("rtp-1", 1000, 1010), "no-body", actor));
    agility(4, AGILITY);
    assertThrows(
        ApiException.class, () -> api.estimate(request("rtp-1", 1000, 1010), "version", actor));
    agility(
        3, new AgilityContracts.Model("sim-x", "sim-v1", "SIMULATION", 30, 2, 5, "synthetic-test"));
    assertThrows(
        ApiException.class, () -> api.estimate(request("rtp-1", 1000, 1010), "craft", actor));
    assertTrue(store.list("maneuver-estimate", 100).isEmpty());
  }

  @Test
  void rejectsMalformedRequests() {
    assertThrows(IllegalArgumentException.class, () -> request("rtp-1", 1000));
    assertThrows(IllegalArgumentException.class, () -> request("rtp-1", 1010, 1000));
    assertThrows(IllegalArgumentException.class, () -> request("rtp-1", 1000, 1000));
    var targets =
        List.of(
            new PointingTarget("rtp-1", MissionInstant.tai(1000)),
            new PointingTarget("rtp-1", MissionInstant.tai(1010)));
    assertThrows(IllegalArgumentException.class, () -> new Request("sim-test", 3, 0, targets));
    assertThrows(IllegalArgumentException.class, () -> new Request("sim-test", 3, 181, targets));
    assertThrows(IllegalArgumentException.class, () -> new Request("sim-test", 0, 1, targets));
  }

  static RequiredTargetPointingResult pointing(
      String spacecraftId, List<RequiredTargetPointingSample> samples) {
    return pointing(spacecraftId, "solution-1", samples);
  }

  static RequiredTargetPointingResult pointing(
      String spacecraftId, String solutionId, List<RequiredTargetPointingSample> samples) {
    var query =
        new RequiredTargetPointingQuery(
            TARGET, new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(1200)), 10, 10);
    return new RequiredTargetPointingResult(
        solutionId,
        spacecraftId,
        "test-propagation",
        "test-pointing",
        "a".repeat(64),
        "reference",
        query,
        samples,
        RequiredTargetPointingScope.SAMPLED_LINE_OF_SIGHT_NOT_ATTITUDE);
  }

  /** Inertial line of sight rotated {@code azimuthDegrees} about EME2000 +Z from +X. */
  static RequiredTargetPointingSample sample(
      long tai, double azimuthDegrees, double offNadirDegrees, boolean visible) {
    return sample(MissionInstant.tai(tai), azimuthDegrees, offNadirDegrees, visible);
  }

  static RequiredTargetPointingSample sample(
      MissionInstant instant, double azimuthDegrees, double offNadirDegrees, boolean visible) {
    double a = Math.toRadians(azimuthDegrees);
    var los = new Trajectory.Vector(Math.cos(a), Math.sin(a), 0);
    return new RequiredTargetPointingSample(
        instant,
        new Trajectory.Vector(7_000_000, 0, 0),
        new Trajectory.Vector(-1, 0, 0),
        los,
        offNadirDegrees,
        700_000,
        new Trajectory.GroundPoint(instant, 0, 0, 0, "reference"),
        TARGET,
        visible ? 45 : -5,
        10,
        visible);
  }
}
