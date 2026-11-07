package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.domain.spacecraftcontrol.CommandLoad;
import msc.domain.spacecraftcontrol.TransmissionRecord;
import msc.platform.*;
import msc.ports.Clock;
import msc.ports.SpacecraftLinkPort;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * SIMULATION link adapter bound to one released submission. Transport is the existing JSON
 * Simulator HTTP API, not CCSDS: no frames, APIDs, transfer protocol or RF are modelled (ADR-0004).
 * The receipt is the Simulator ledger, which is simulation evidence, not spacecraft execution.
 */
final class SimulationSpacecraftLink implements SpacecraftLinkPort {
  private final ServiceHttp http;
  private final Json json;
  private final Clock clock;
  private final String loadId;
  private final UUID scenarioId;
  private final JsonNode submission;
  private JsonNode receipt;

  SimulationSpacecraftLink(
      ServiceHttp http, Json json, Clock clock, String loadId, UUID scenarioId, JsonNode submission) {
    this.http = http;
    this.json = json;
    this.clock = clock;
    this.loadId = loadId;
    this.scenarioId = scenarioId;
    this.submission = submission;
  }

  private String path() {
    return "/internal/simulation/scenarios/" + scenarioId + "/loads";
  }

  private JsonNode validated(JsonNode ledger) {
    if (!loadId.equals(ledger.path("id").asText())
        || ledger.path("version").asLong() < 1
        || !json.fingerprint(ledger.path("body").path("load"))
            .equals(json.fingerprint(submission.path("load")))
        || !json.fingerprint(submission).equals(ledger.path("body").path("submissionSha256").asText()))
      throw ApiException.invalid("Simulator ledger does not match the released submission");
    return ledger;
  }

  /** Reads the receiver ledger; HTTP errors, including an authoritative 404, propagate. */
  JsonNode observe() {
    return validated(
        http.get(
            "simulator",
            path() + "/" + UriUtils.encodePathSegment(loadId, StandardCharsets.UTF_8),
            JsonNode.class));
  }

  /** Validated ledger returned by the last successful {@link #transmit}. */
  JsonNode receipt() {
    return receipt;
  }

  @Override
  public TransmissionRecord transmit(CommandLoad authorizedLoad) {
    if (!loadId.equals(authorizedLoad.id().value())
        || !json.fingerprint(authorizedLoad).equals(json.fingerprint(submission.path("load"))))
      throw new IllegalArgumentException("Load is not the released simulation submission");
    JsonNode ledger;
    try {
      // Immutable submission and stable key let the Simulator deduplicate any retry.
      ledger = http.post("simulator", path(), submission, "control-v1:" + loadId, JsonNode.class);
    } catch (RestClientException unavailable) {
      throw new CommandReleaseGate.TransmissionUnconfirmed("DELIVERY_NOT_CONFIRMED", unavailable);
    }
    receipt = validated(ledger);
    return new TransmissionRecord(
        authorizedLoad.id(),
        clock.now(),
        "simulation-scenario:" + scenarioId,
        "simulator-ledger:" + loadId + "@" + ledger.path("version").asLong());
  }
}
