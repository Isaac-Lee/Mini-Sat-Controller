package msc.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;
import msc.domain.time.MissionInstant;

public record ServiceEvent(
    UUID eventId,
    int schemaVersion,
    String type,
    String aggregateId,
    long aggregateVersion,
    UUID correlationId,
    UUID causationId,
    MissionInstant occurredAt,
    JsonNode payload) {
  public ServiceEvent {
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(type);
    Objects.requireNonNull(aggregateId);
    Objects.requireNonNull(correlationId);
    Objects.requireNonNull(occurredAt);
    Objects.requireNonNull(payload);
    if (schemaVersion != 1 || aggregateVersion < 1)
      throw new IllegalArgumentException("Unsupported event envelope");
  }
}
