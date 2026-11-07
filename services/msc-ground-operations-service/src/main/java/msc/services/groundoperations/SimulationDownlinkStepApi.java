package msc.services.groundoperations;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.platform.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * Operator-initiated simulated downlink steps (ADR-0014): allocate a synthetic onboard payload to a
 * ground-owned booking, and request one simulated reception attempt. Both relay to the simulator's
 * SERVICE-only endpoints, which validate SIMULATION scope, booking state and capacity and decide
 * every outcome. Not RF and not a real ground station.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class SimulationDownlinkStepApi {
  /** {@code imagingCommandId} names the command whose synthetic payload is downlinked. */
  public record PlanRequest(UUID scenarioId, String loadId, String commandId, String imagingCommandId) {
    public PlanRequest {
      Objects.requireNonNull(scenarioId);
      for (var value : List.of(loadId, commandId, imagingCommandId))
        msc.domain.shared.Checks.text(value);
    }
  }

  public enum Channel {
    ACKNOWLEDGMENT,
    RECONCILIATION
  }

  public record Attempt(Channel channel) {
    public Attempt {
      Objects.requireNonNull(channel);
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;

  public SimulationDownlinkStepApi(StateStore store, ServiceHttp http, Json json) {
    this.store = store;
    this.http = http;
    this.json = json;
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void requireKey(String key) {
    msc.domain.shared.Checks.text(key);
    if (key.length() > 100) throw ApiException.invalid("Idempotency-Key too long");
  }

  // The simulator sees one SERVICE principal: the operator is part of the hashed, fixed-length key.
  private static String key(String step, String subject, Authentication actor, String key) {
    return "ground-v1:" + step + ":" + sha256(subject + "\n" + actor.getName() + "\n" + key);
  }

  @PostMapping("/api/bookings/{id}/simulation-downlink-plan")
  public JsonNode plan(
      @PathVariable String id,
      @RequestBody PlanRequest request,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    requireKey(key);
    // Only a booking this service owns; its state and capacity are checked by the simulator.
    store.require("booking", id, JsonNode.class);
    // Payload identity is derived server-side exactly as the simulator names synthetic payloads,
    // never accepted from the client.
    String payloadId =
        json.fingerprint(List.of(request.scenarioId().toString(), request.imagingCommandId()));
    var body =
        Map.of(
            "scenarioId", request.scenarioId(),
            "loadId", request.loadId(),
            "commandId", request.commandId(),
            "payloadId", payloadId,
            "bookingId", id);
    return relay(
        () ->
            http.post(
                "simulator",
                "/internal/simulation/downlinks",
                body,
                key("plan", id, actor, key),
                JsonNode.class));
  }

  @PostMapping("/api/simulation-downlinks/{planId}/receive")
  public JsonNode receive(
      @PathVariable String planId,
      @RequestBody Attempt attempt,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    requireKey(key);
    String path =
        "/internal/simulation/downlinks/"
            + UriUtils.encodePathSegment(planId, StandardCharsets.UTF_8)
            + "/receive";
    return relay(
        () ->
            http.post(
                "simulator",
                path,
                Map.of("channel", attempt.channel()),
                key("receive", planId, actor, key),
                JsonNode.class));
  }

  private JsonNode relay(java.util.function.Supplier<JsonNode> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden denied) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY, "SIMULATOR_AUTH_FAILED", "Simulator refused the service call");
    } catch (HttpClientErrorException rejected) {
      // Plan allocation fails for many distinct reasons; pass the simulator's own explanation on.
      var status = HttpStatus.resolve(rejected.getStatusCode().value());
      String message = "Simulator rejected the downlink step";
      try {
        var reason = json.read(rejected.getResponseBodyAsString(), JsonNode.class).path("message");
        if (reason.isTextual() && !reason.asText().isBlank())
          message = "Simulator: " + reason.asText();
      } catch (RuntimeException unreadable) {
        // Keep the generic message for a non-JSON body.
      }
      throw new ApiException(
          status == null ? HttpStatus.BAD_GATEWAY : status, "SIMULATOR_REJECTED", message);
    } catch (RestClientException unavailable) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "SIMULATOR_UNAVAILABLE", "Simulator unavailable");
    }
  }
}
