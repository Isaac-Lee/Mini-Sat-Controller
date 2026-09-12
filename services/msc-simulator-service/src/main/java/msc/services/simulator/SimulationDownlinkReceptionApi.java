package msc.services.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.BigDecimal;
import java.security.*;
import java.util.*;
import msc.contracts.GroundContracts.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.services.simulator.SimulationCommandApi.*;
import msc.services.simulator.SimulationDownlinkPlanApi.Plan;
import msc.services.simulator.SimulationReceptionApi.*;
import msc.services.simulator.SimulationScenarioApi.Scenario;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Atomic-completion station model; received bytes, not physical RF/contact qualification. */
@RestController
@ConditionalOnProperty(name = "msc.s3.enabled", havingValue = "true")
public class SimulationDownlinkReceptionApi {
  public record Receipt(
      String planId,
      String planSha256,
      String stationId,
      long ledgerVersion,
      long linkVersion,
      long byteCount,
      String sha256,
      String objectReference,
      MissionInstant receivedAt,
      String environment) {}

  public record Result(Belief belief, String reason, Optional<Receipt> receipt) {}

  record Gate(
      String issue,
      StateStore.State<Ledger> ledger,
      StateStore.State<Configure> link,
      StateStore.State<Scenario> scenario,
      Booking booking) {}

  private final StateStore store;
  private final Json json;
  private final ObjectStorage objects;

  public SimulationDownlinkReceptionApi(StateStore store, Json json, ObjectStorage objects) {
    this.store = store;
    this.json = json;
    this.objects = objects;
  }

  @PostMapping("/internal/simulation/downlinks/{id}/receive")
  @PreAuthorize("hasRole('SERVICE')")
  public JsonNode receive(
      @PathVariable String id,
      @RequestBody Attempt request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor)
      throws IOException {
    String scope = "simulation-downlink-receive:" + id + ":" + actor.getName();
    var replay = store.replay(scope, key, request);
    if (replay.isPresent()) return replay.get();
    var plan = store.require("simulation-downlink-plan", id, Plan.class).body();
    var prior = store.find("simulation-downlink-receipt", id, Receipt.class);
    if (prior.isPresent())
      return store.idempotent(scope, key, request, () -> observed(prior.get().body()));
    var before = store.transaction(() -> check(plan, request));
    if (before.issue() != null)
      return store.idempotent(scope, key, request, () -> unknown(before.issue()));
    var payload = plan.payload();
    if (payload.byteCount() < 1 || payload.byteCount() > 64L * 1024 * 1024)
      throw ApiException.invalid("Payload byte count outside bounded receiver range");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    long count = 0;
    try (var input = objects.read(payload.objectReference())) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = input.read(buffer)) != -1) {
        count += n;
        if (count > payload.byteCount()) throw ApiException.invalid("Payload contains extra bytes");
        digest.update(buffer, 0, n);
      }
    }
    if (count != payload.byteCount()
        || !HexFormat.of().formatHex(digest.digest()).equals(payload.sha256()))
      throw ApiException.invalid("Received payload size/hash mismatch");
    return store.idempotent(
        scope,
        key,
        request,
        () -> {
          var after = check(plan, request);
          var received = store.find("simulation-downlink-receipt", id, Receipt.class);
          if (received.isPresent()) return observed(received.get().body());
          if (after.issue() != null) return unknown(after.issue());
          if (!json.fingerprint(before).equals(json.fingerprint(after)))
            return unknown("RECEPTION_CONTEXT_CHANGED");
          var c = after.scenario().body().correlation().body();
          var receipt =
              new Receipt(
                  id,
                  json.fingerprint(plan),
                  after.booking().request().stationId(),
                  after.ledger().version(),
                  after.link().version(),
                  payload.byteCount(),
                  payload.sha256(),
                  payload.objectReference(),
                  c.tickToTai(
                      c.timeCorrelationId(),
                      c.clockPartition(),
                      after.scenario().body().currentTick()),
                  "SIMULATION");
          var saved = store.create("simulation-downlink-receipt", id, receipt);
          store.event(
              "SimulatedPayloadReceived",
              after.scenario().body().mission().spacecraftId(),
              saved.version(),
              UUID.randomUUID(),
              null,
              saved);
          return observed(receipt);
        });
  }

  private Gate check(Plan plan, Attempt request) {
    var binding = plan.request();
    store.lock("booking:" + binding.bookingId());
    store.lock("simulation-scenario:" + binding.scenarioId());
    var booking = store.require("booking", binding.bookingId(), Booking.class).body();
    var scenario =
        store.require("simulation-scenario", binding.scenarioId().toString(), Scenario.class);
    var ledger =
        store.require("simulation-load:" + binding.scenarioId(), binding.loadId(), Ledger.class);
    var link =
        store
            .find("simulation-link:" + binding.scenarioId(), binding.loadId(), Configure.class)
            .orElse(null);
    String issue = null;
    if (booking.status() != BookingStatus.CONFIRMED || !booking.equals(plan.booking()))
      issue = "BOOKING_NOT_CONFIRMED";
    else if (link == null) issue = "LINK_NOT_CONFIGURED";
    else if (!link.body().connected()) issue = "DISCONNECTED";
    else if (scenario.body().currentTick() < link.body().notBeforeTick()) issue = "DELAYED";
    else if (request.channel() == Channel.ACKNOWLEDGMENT && link.body().acknowledgmentLost())
      issue = "ACKNOWLEDGMENT_LOST";
    else {
      var entry =
          ledger.body().entries().stream()
              .filter(e -> e.command().id().value().equals(binding.commandId()))
              .findFirst()
              .orElseThrow();
      var original = plan.command();
      if (!entry.command().equals(original.command())
          || !entry.catalog().equals(original.catalog())
          || !entry.profile().equals(original.profile())
          || entry.startTick() != original.startTick()
          || entry.completionTick() != original.completionTick())
        throw ApiException.invalid("Downlink binding changed");
      if (entry.status() != Status.EFFECT_APPLIED
          || entry.effect() == null
          || entry.effect().outcome() != SimulatorOperationEffects.Outcome.APPLIED
          || scenario.body().currentTick() < entry.completionTick())
        issue = "DOWNLINK_EFFECT_NOT_OBSERVED";
      else if (BigDecimal.valueOf(entry.effect().actualDrainMegabytes())
              .movePointRight(6)
              .compareTo(BigDecimal.valueOf(plan.payload().byteCount()))
          < 0) issue = "INSUFFICIENT_MODELED_DOWNLINK_BYTES";
    }
    return new Gate(issue, ledger, link, scenario, booking);
  }

  private static Result unknown(String issue) {
    return new Result(Belief.UNKNOWN, issue, Optional.empty());
  }

  private static Result observed(Receipt receipt) {
    return new Result(Belief.OBSERVED, "RECEIVED", Optional.of(receipt));
  }

  @GetMapping("/internal/simulation/downlinks/{id}/received-content")
  @PreAuthorize("hasRole('SERVICE')")
  public org.springframework.http.ResponseEntity<
          org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      content(@PathVariable String id) {
    var receipt = read(id).body();
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(receipt.byteCount())
        .header("X-MSC-Environment", "SIMULATION")
        .body(
            output -> {
              try (var input = objects.read(receipt.objectReference())) {
                input.transferTo(output);
              }
            });
  }

  @GetMapping("/internal/simulation/downlinks/{id}/receipt")
  @PreAuthorize("hasRole('SERVICE')")
  public StateStore.State<Receipt> read(@PathVariable String id) {
    return store.require("simulation-downlink-receipt", id, Receipt.class);
  }
}
