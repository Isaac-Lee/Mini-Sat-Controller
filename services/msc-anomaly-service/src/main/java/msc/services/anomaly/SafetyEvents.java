package msc.services.anomaly;

import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public final class SafetyEvents implements EventHandler {
  private final SafetyStore safety;
  private final SafetyEvidence evidence;

  public SafetyEvents(SafetyStore safety, SafetyEvidence evidence) {
    this.safety = safety;
    this.evidence = evidence;
  }

  public void handle(ServiceEvent event) {
    if (event.type().equals("SpacecraftStateProjected")) {
      var reading = evidence.decode(event.payload());
      if (!event.aggregateId().equals(reading.spacecraftId())
          || event.aggregateVersion() != reading.version())
        throw ApiException.invalid("Monitoring event binding mismatch");
      safety.observe(reading, event.correlationId(), event.eventId());
    }
  }
}
