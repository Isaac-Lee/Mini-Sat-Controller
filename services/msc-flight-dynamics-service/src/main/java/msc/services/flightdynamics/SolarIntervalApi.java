package msc.services.flightdynamics;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.IlluminationContracts.TargetIlluminationQuery;
import msc.contracts.SolarIntervalContracts.Assumptions;
import msc.orbit.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/** Owner-pinned interval evidence, conditional on explicitly published simulation assumptions. */
@RestController
public class SolarIntervalApi {
  public record Request(String spacecraftId, long assumptionsVersion, TargetIlluminationQuery query) {
    public Request {
      msc.domain.shared.Checks.text(spacecraftId);
      if (assumptionsVersion <= 0) throw new IllegalArgumentException("Exact positive assumptions version required");
      Objects.requireNonNull(query);
    }
  }
  public enum Outcome { SUPPORTED_BY_DECLARED_ASSUMPTIONS, NOT_ESTABLISHED }
  public record Result(Request request, StateStore.State<Assumptions> assumptions,
      String assumptionsSha256, String solarModelAccuracyNote,
      ConditionalSolarInterval.Result interval, Outcome outcome) {}

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final OrekitReferenceFrames references;

  public SolarIntervalApi(StateStore store, ServiceHttp http, Json json, OrekitReferenceFrames references) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.references = references;
  }

  @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class)
  @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
  public ApiErrors.Error missingOwner() { return new ApiErrors.Error("NOT_FOUND", "Solar interval assumptions version not found"); }

  @PostMapping({"/api/solar-intervals", "/internal/solar-intervals"})
  @PreAuthorize("hasAnyRole('OPERATOR','SERVICE')")
  public JsonNode calculate(@RequestBody Request request,
      @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    String scope = "solar-interval:" + actor.getName();
    var prior = store.replay(scope, key, request);
    if (prior.isPresent()) return prior.get();
    var envelope = http.get("mission-definition", "/internal/solar-interval-assumptions/"
        + UriUtils.encodePathSegment(request.spacecraftId(), StandardCharsets.UTF_8)
        + "/versions/" + request.assumptionsVersion(), JsonNode.class);
    if (!request.spacecraftId().equals(envelope.path("id").asText())
        || !envelope.path("version").isIntegralNumber()
        || !envelope.path("version").canConvertToLong()
        || envelope.path("version").longValue() != request.assumptionsVersion())
      throw ApiException.invalid("Assumptions owner identity/version mismatch");
    var assumptions = json.convert(envelope.get("body"), Assumptions.class);
    if (!request.spacecraftId().equals(assumptions.spacecraftId()))
      throw ApiException.invalid("Assumptions spacecraft mismatch");
    assumptions.requireCoverage(OrekitIlluminationPredictor.SOLAR_MODEL, references.digest(),
        request.query().aoi(), request.query().horizon());
    var area = request.query().aoi();
    var interval = new OrekitIlluminationPredictor(references).rectangularInterval(
        request.query().horizon(), new RectangularSolarElevation.Rectangle(
            area.westLongitudeDegrees(), area.eastLongitudeDegrees(), area.southLatitudeDegrees(),
            area.northLatitudeDegrees(), area.altitudeMeters()),
        new ConditionalSolarInterval.Assumptions(assumptions.maximumRateRadiansPerSecond(),
            assumptions.evaluationErrorRadians(), assumptions.maximumStepSeconds(), assumptions.justificationReference()));
    var source = new StateStore.State<>(request.spacecraftId(), request.assumptionsVersion(), assumptions);
    var result = new Result(request, source, json.fingerprint(source),
        OrekitIlluminationPredictor.SOLAR_MODEL_ACCURACY_NOTE, interval,
        interval.lowerElevationRadians() >= Math.nextUp(Math.toRadians(request.query().minimumSunElevationDegrees()))
            ? Outcome.SUPPORTED_BY_DECLARED_ASSUMPTIONS : Outcome.NOT_ESTABLISHED);
    return store.idempotent(scope, key, request, () -> {
      var saved = store.create("solar-interval", UUID.randomUUID().toString(), result);
      store.event("SolarIntervalEvaluated", saved.id(), saved.version(), UUID.randomUUID(), null, result);
      return saved;
    });
  }

  @GetMapping({"/api/solar-intervals/{id}", "/internal/solar-intervals/{id}"})
  public Result read(@PathVariable String id) {
    return store.require("solar-interval", id, Result.class).body();
  }
}
