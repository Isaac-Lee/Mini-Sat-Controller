package msc.services.monitoring;

import msc.domain.monitoring.OperationalTelemetry.Frame;
import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryEvents implements EventHandler {
  private final TelemetryStore telemetry;
  private final Json json;

  public TelemetryEvents(TelemetryStore telemetry, Json json) {
    this.telemetry = telemetry;
    this.json = json;
  }

  public void handle(ServiceEvent event) {
    if (event.type().equals("TelemetryReceived")) {
      var frame = json.convert(event.payload(), Frame.class);
      if (!event.aggregateId().equals(frame.spacecraftId()))
        throw ApiException.invalid("Telemetry envelope spacecraft mismatch");
      telemetry.ingest(frame, event.correlationId(), event.eventId());
    }
    // Execution evidence is owned by Control/Acquisition; it does not fabricate sensor readings.
  }
}
