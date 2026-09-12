package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.contracts.OperationResourceContracts.Operation;
import msc.platform.*;
import msc.services.simulator.SimulationCommandApi.*;
import msc.services.simulator.SimulationScenarioApi.Scenario;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit payload allocation before a simulated DOWNLINK starts. Not reception evidence. */
@RestController
public class SimulationDownlinkPlanApi {
  public record Request(
      UUID scenarioId, String loadId, String commandId, String payloadId, String bookingId) {
    public Request {
      Objects.requireNonNull(scenarioId);
      for (var value : List.of(loadId, commandId, payloadId, bookingId))
        msc.domain.shared.Checks.text(value);
    }
  }

  public record Plan(
      Request request,
      SimulationPayload.Manifest payload,
      Booking booking,
      Entry command,
      String sourceSha256) {}

  private final StateStore store;
  private final Json json;

  public SimulationDownlinkPlanApi(StateStore store, Json json) {
    this.store = store;
    this.json = json;
  }

  @PostMapping("/internal/simulation/downlinks")
  @PreAuthorize("hasRole('SERVICE')")
  public JsonNode plan(
      @RequestBody Request request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "simulation-downlink-plan:" + actor.getName(),
        key,
        request,
        () -> {
          store.lock("booking:" + request.bookingId());
          store.lock("simulation-scenario:" + request.scenarioId());
          String id =
              json.fingerprint(List.of(request.scenarioId().toString(), request.commandId()));
          var existing = store.find("simulation-downlink-plan", id, Plan.class);
          if (existing.isPresent()) {
            if (!existing.get().body().request().equals(request))
              throw ApiException.conflict("Downlink command already allocated");
            return existing.get();
          }
          var scenario =
              store
                  .require("simulation-scenario", request.scenarioId().toString(), Scenario.class)
                  .body();
          var load =
              store
                  .require(
                      "simulation-load:" + request.scenarioId(), request.loadId(), Ledger.class)
                  .body();
          var entry =
              load.entries().stream()
                  .filter(e -> e.command().id().value().equals(request.commandId()))
                  .findFirst()
                  .orElseThrow(() -> ApiException.missing("Downlink command not found"));
          var payload =
              store
                  .require(
                      "simulation-payload", request.payloadId(), SimulationPayload.Manifest.class)
                  .body();
          var booking = store.require("booking", request.bookingId(), Booking.class).body();
          validate(scenario, entry, payload, booking);
          var station =
              store
                  .require(
                      "station",
                      booking.request().stationId() + ":" + booking.request().stationVersion(),
                      Station.class)
                  .body();
          msc.contracts.GroundContracts.validateCapacity(station, booking.request());
          if (BigDecimal.valueOf(payload.byteCount())
                  .compareTo(
                      BigDecimal.valueOf(station.downlinkMegabytesPerSecond())
                          .multiply(BigDecimal.valueOf(entry.catalog().durationSeconds()))
                          .movePointRight(6))
              > 0)
            throw ApiException.invalid("Payload exceeds station capacity during command interval");
          String allocations = "simulation-downlink-booking:" + request.bookingId();
          var prior = store.list(allocations, 500);
          if (prior.size() == 500) throw ApiException.conflict("Booking allocation limit reached");
          BigDecimal allocated =
              prior.stream()
                  .map(s -> BigDecimal.valueOf(json.convert(s.body(), Long.class)))
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .add(BigDecimal.valueOf(payload.byteCount()));
          if (allocated.compareTo(
                  BigDecimal.valueOf(booking.request().requestedMegabytes()).movePointRight(6))
              > 0) throw ApiException.conflict("Payload allocations exceed booking volume");
          var result =
              new Plan(
                  request,
                  payload,
                  booking,
                  entry,
                  json.fingerprint(List.of(scenario, load, payload, booking, station)));
          store.create(allocations, id, payload.byteCount());
          return store.create("simulation-downlink-plan", id, result);
        });
  }

  static void validate(
      Scenario scenario, Entry entry, SimulationPayload.Manifest payload, Booking booking) {
    if (!"SIMULATION".equals(scenario.environment())
        || !"SIMULATION".equals(payload.environment())
        || !"ONBOARD".equals(payload.location())
        || !scenario.id().toString().equals(payload.source().scenarioId()))
      throw ApiException.invalid("Payload does not belong to this onboard simulation");
    if (entry.status() != Status.PENDING
        || entry.profile().operation() != Operation.DOWNLINK
        || scenario.currentTick() >= entry.startTick()
        || payload.source().completionTick() > scenario.currentTick())
      throw ApiException.conflict("Payload must be allocated before a pending DOWNLINK starts");
    if (booking.status() != BookingStatus.CONFIRMED
        || !scenario.mission().spacecraftId().equals(booking.request().spacecraftId()))
      throw ApiException.conflict("Confirmed spacecraft booking required");
    var c = scenario.correlation().body();
    var start = c.tickToTai(c.timeCorrelationId(), c.clockPartition(), entry.startTick());
    var end = c.tickToTai(c.timeCorrelationId(), c.clockPartition(), entry.completionTick());
    if (start.compareTo(booking.request().window().start()) < 0
        || end.compareTo(booking.request().window().end()) > 0)
      throw ApiException.invalid("Downlink command must fit the booked interval");
    BigDecimal capacity =
        BigDecimal.valueOf(entry.profile().downlinkMegabytesPerSecond())
            .multiply(BigDecimal.valueOf(entry.catalog().durationSeconds()))
            .movePointRight(6);
    if (payload.byteCount() <= 0 || BigDecimal.valueOf(payload.byteCount()).compareTo(capacity) > 0)
      throw ApiException.invalid("Payload exceeds command downlink capacity");
  }

  @GetMapping("/internal/simulation/downlinks/{id}")
  @PreAuthorize("hasRole('SERVICE')")
  public StateStore.State<Plan> read(@PathVariable String id) {
    return store.require("simulation-downlink-plan", id, Plan.class);
  }
}
