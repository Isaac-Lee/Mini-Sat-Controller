package msc.services.spacecraftcontrol;

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
 * Operator-initiated simulated ground reception of a released SIMULATION load (ADR-0014). It only
 * relays one attempt to the simulator's SERVICE-only receive step; the simulator decides the
 * outcome (UNKNOWN with a reason, or OBSERVED) and nothing here manufactures evidence. Not RF.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class SimulationReceptionStepApi {
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

  static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public SimulationReceptionStepApi(StateStore store, ServiceHttp http) {
    this.store = store;
    this.http = http;
  }

  @PostMapping("/api/command-loads/{id}/simulation-reception")
  public JsonNode receive(
      @PathVariable String id,
      @RequestBody Attempt attempt,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    msc.domain.shared.Checks.text(key);
    if (key.length() > 100) throw ApiException.invalid("Idempotency-Key too long");
    var release = store.require(SimulationDispatchApi.RELEASE, id, JsonNode.class).body();
    if (!"SIMULATION".equals(release.path("environment").asText()))
      throw ApiException.conflict("Reception steps exist only for SIMULATION releases");
    var scenario = UUID.fromString(release.path("scenarioId").asText());
    String path =
        "/internal/simulation/scenarios/"
            + scenario
            + "/loads/"
            + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8)
            + "/receive";
    // Each operator attempt carries its own key: a DELAYED/DISCONNECTED answer must not be replayed
    // for a later attempt, while a retried attempt (same key) is deduplicated by the simulator. The
    // simulator sees one SERVICE principal, so the operator is part of the hashed, fixed-length key.
    try {
      return http.post(
          "simulator",
          path,
          Map.of("channel", attempt.channel()),
          "control-v1:recv:" + sha256(id + "\n" + actor.getName() + "\n" + key),
          JsonNode.class);
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden denied) {
      // Our SERVICE credential was refused; never surface it as the operator's own auth failure.
      throw new ApiException(
          HttpStatus.BAD_GATEWAY, "SIMULATOR_AUTH_FAILED", "Simulator refused the service call");
    } catch (HttpClientErrorException rejected) {
      throw new ApiException(
          HttpStatus.valueOf(rejected.getStatusCode().value()),
          "SIMULATOR_REJECTED",
          "Simulator rejected the reception attempt");
    } catch (RestClientException unavailable) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "SIMULATOR_UNAVAILABLE", "Simulator unavailable");
    }
  }
}
