package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.contracts.IlluminationContracts.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class IlluminationContractsTest {
  private static MissionInstant t(long seconds) {
    return MissionInstant.tai(seconds);
  }

  private static TimeWindow w(long start, long end) {
    return new TimeWindow(t(start), t(end));
  }

  private Aoi aoi() {
    return new Aoi("aoi-1", 10, 20, -5, 5, 0);
  }

  // A7: antimeridian-crossing and pole-containing AOIs are rejected explicitly.
  @Test
  void rejectsAntimeridianCrossingAoi() {
    assertThrows(IllegalArgumentException.class, () -> new Aoi("aoi", 170, -170, -5, 5, 0));
    assertThrows(IllegalArgumentException.class, () -> new Aoi("aoi", 20, 20, -5, 5, 0));
  }

  @Test
  void rejectsPoleContainingOrApproachingAoi() {
    assertThrows(IllegalArgumentException.class, () -> new Aoi("aoi", 10, 20, -5, 90, 0));
    assertThrows(IllegalArgumentException.class, () -> new Aoi("aoi", 10, 20, -90, 5, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Aoi("aoi", 10, 20, -5, IlluminationContracts.POLE_GUARD_DEGREES + 0.5, 0));
  }

  @Test
  void fivePointSampleIsCentreThenFourCornersInFixedOrder() {
    var points = aoi().fivePointSample();
    assertEquals(5, points.size());
    assertEquals("aoi-1:centre", points.get(0).id());
    assertEquals(0.0, points.get(0).latitudeDegrees());
    assertEquals(15.0, points.get(0).longitudeDegrees());
    assertEquals(Set.of("aoi-1:centre", "aoi-1:nw", "aoi-1:ne", "aoi-1:sw", "aoi-1:se"),
        points.stream().map(GroundPoint::id).collect(java.util.stream.Collectors.toSet()));
    // Corners are exactly the AOI edges, never a naive re-centring.
    for (var p : points.subList(1, 5)) {
      assertTrue(p.latitudeDegrees() == -5 || p.latitudeDegrees() == 5);
      assertTrue(p.longitudeDegrees() == 10 || p.longitudeDegrees() == 20);
    }
  }

  // A6: five-point sampling never yields a bare full-AOI claim, and centre-only sampling can
  // never be represented as an AOI-level result at all.
  @Test
  void aoiIlluminationScopeHasNoFullAoiValue() {
    var values = AoiIlluminationScope.values();
    assertEquals(1, values.length);
    assertEquals("SAMPLED_POINTS_ONLY", values[0].name());
    assertTrue(
        Arrays.stream(values).noneMatch(v -> v.name().contains("FULL_AOI")),
        "No enum constant may claim full-AOI proof");
    assertTrue(
        Arrays.stream(values).noneMatch(v -> v.name().contains("LOWER_BOUND")),
        "No enum constant may claim a lower bound: sampling intersection is an"
            + " over-approximation, not a lower bound, of full-AOI illumination");
  }

  @Test
  void centreOnlySamplingCanNeverConstructAnAoiLevelResult() {
    var centre = new GroundPoint("aoi-1:centre", 0, 15, 0);
    var centreOnly =
        List.of(new SampledPointIllumination("aoi-1:centre", 0, 15, List.of(w(0, 10))));
    var query = new TargetIlluminationQuery(aoi(), w(0, 100), 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetIlluminationResult(
                "sim-1",
                "solar-model",
                "accuracy-note",
                "digest",
                query,
                0.001,
                60,
                centreOnly,
                List.of(w(0, 10)),
                AoiIlluminationScope.SAMPLED_POINTS_ONLY));
  }

  @Test
  void targetIlluminationResultRequiresExactlyFivePoints() {
    var query = new TargetIlluminationQuery(aoi(), w(0, 100), 5);
    var points = aoi().fivePointSample();
    var perPoint = new ArrayList<SampledPointIllumination>();
    for (var p : points)
      perPoint.add(new SampledPointIllumination(p.id(), p.latitudeDegrees(), p.longitudeDegrees(), List.of(w(0, 10))));
    var result =
        new TargetIlluminationResult(
            "sim-1",
            "solar-model",
            "accuracy-note",
            "digest",
            query,
            0.001,
            60,
            perPoint,
            IlluminationContracts.intersectAll(perPoint.stream().map(SampledPointIllumination::illuminatedWindows).toList()),
            AoiIlluminationScope.SAMPLED_POINTS_ONLY);
    assertEquals(5, result.points().size());
    assertEquals(List.of(w(0, 10)), result.sampledPointsIntersection());
  }

  // A2: model id and accuracy note must be present (non-blank) in every stored result.
  @Test
  void resultsRequireNonBlankModelAndAccuracyNote() {
    var query = new TargetIlluminationQuery(aoi(), w(0, 100), 5);
    var points = aoi().fivePointSample().stream()
        .map(p -> new SampledPointIllumination(p.id(), p.latitudeDegrees(), p.longitudeDegrees(), List.of()))
        .toList();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetIlluminationResult(
                "sim-1", "", "accuracy-note", "digest", query, 0.001, 60, points, List.of(),
                AoiIlluminationScope.SAMPLED_POINTS_ONLY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetIlluminationResult(
                "sim-1", "model", "", "digest", query, 0.001, 60, points, List.of(),
                AoiIlluminationScope.SAMPLED_POINTS_ONLY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpacecraftEclipseResult(
                "sol-1", "sim-1", "model", "", "digest", w(0, 100), 0.001, 10, List.of(), List.of()));
  }

  // Root compatibility rule: orbitPropagationModel defaults/normalizes to empty (legacy,
  // unbound) so historical rows without the field keep working; the preserved ten-argument
  // constructor produces that same empty value; and a present value must still be non-blank,
  // like every other identity field on this record.
  @Test
  void orbitPropagationModelDefaultsToEmptyAndNormalizesNullButRejectsBlank() {
    var viaOldConstructor =
        new SpacecraftEclipseResult(
            "sol-1", "sim-1", "model", "note", "digest", w(0, 100), 0.001, 10, List.of(), List.of());
    assertTrue(viaOldConstructor.orbitPropagationModel().isEmpty());

    var explicitNull =
        new SpacecraftEclipseResult(
            "sol-1", "sim-1", "model", "note", "digest", w(0, 100), 0.001, 10, List.of(), List.of(),
            null);
    assertTrue(explicitNull.orbitPropagationModel().isEmpty());

    var populated =
        new SpacecraftEclipseResult(
            "sol-1", "sim-1", "model", "note", "digest", w(0, 100), 0.001, 10, List.of(), List.of(),
            Optional.of("orekit-13.1.8-SGP4-SDP4-public-GP"));
    assertEquals(
        Optional.of("orekit-13.1.8-SGP4-SDP4-public-GP"), populated.orbitPropagationModel());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SpacecraftEclipseResult(
                "sol-1", "sim-1", "model", "note", "digest", w(0, 100), 0.001, 10, List.of(),
                List.of(), Optional.of("   ")));
  }

  @Test
  void intersectAllIsEmptyForNoInputAndForDisjointWindows() {
    assertEquals(List.of(), IlluminationContracts.intersectAll(List.of()));
    assertEquals(
        List.of(),
        IlluminationContracts.intersectAll(List.of(List.of(w(0, 10)), List.of(w(20, 30)))));
  }

  @Test
  void intersectAllOfIdenticalSingleWindowIsThatWindow() {
    var result =
        IlluminationContracts.intersectAll(List.of(List.of(w(0, 10)), List.of(w(0, 10)), List.of(w(0, 10))));
    assertEquals(List.of(w(0, 10)), result);
  }

  @Test
  void intersectAllNarrowsToTheOverlapAndIsOrdered() {
    var perPoint =
        List.of(
            List.of(w(0, 10), w(20, 30)),
            List.of(w(5, 25)));
    var result = IlluminationContracts.intersectAll(perPoint);
    assertEquals(List.of(w(5, 10), w(20, 25)), result);
    for (int i = 1; i < result.size(); i++)
      assertTrue(result.get(i - 1).end().compareTo(result.get(i).start()) <= 0);
  }
}
