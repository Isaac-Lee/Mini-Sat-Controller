package msc.services.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import msc.domain.monitoring.OperationalTelemetry.Estimate;
import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public class SafetyEvidence {
  public record Reading(String spacecraftId, long version, Estimate estimate) {}

  private final ServiceHttp http;
  private final Json json;

  public SafetyEvidence(ServiceHttp http, Json json) {
    this.http = http;
    this.json = json;
  }

  public Reading current(String craft) {
    var result =
        http.get("monitoring", "/internal/spacecraft-estimates/" + craft, JsonNode.class)
            .get("estimate");
    return decode(result);
  }

  public Reading decode(JsonNode state) {
    var result =
        new Reading(
            state.get("id").asText(),
            state.get("version").asLong(),
            json.convert(state.get("body"), Estimate.class));
    if (result.version() < 1
        || !result.spacecraftId().equals(result.estimate().binding().spacecraftId()))
      throw ApiException.invalid("Monitoring evidence identity mismatch");
    return result;
  }
}
