package msc.platform;

import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class EventTopology {
  public static final String EXCHANGE = "msc.events.v1";
  public static final Map<String, List<String>> ROUTES =
      Map.ofEntries(
          Map.entry(
              "tasking",
              List.of(
                  "TargetResolved",
                  "ProductQualityAssessed",
                  "ScheduleAssignmentCommitted",
                  "PlanningRejected")),
          Map.entry(
              "planning",
              List.of(
                  "ObservationRequestAccepted",
                  "ObservationRequestInvalidated",
                  "ReferenceSnapshotPublished",
                  "PlanningFrozen",
                  "PlanningFreezeCleared",
                  "SpacecraftStateProjected",
                  "OperationalOrbitDesignated",
                  "PublicOrbitReferenceCollected",
                  "MissionDefinitionPublished",
                  "ActivityDefinitionPublished",
                  "AgilityModelPublished",
                  "SimulationPlanningModelPublished",
                  "MissionCatalogBindingsPublished",
                  "OperationResourceProfilesPublished",
                  "PropellantModelPublished",
                  "WeatherForecastPublished",
                  "StationDefinitionPublished",
                  "StationBookingRequested",
                  "StationBookingCancelled",
                  "StationBookingRejected",
                  "StationBookingConfirmed")),
          Map.entry(
              "spacecraft-control",
              List.of("ScheduleVersionCommitted", "SpacecraftExecutionObserved")),
          Map.entry("monitoring", List.of("TelemetryReceived", "SpacecraftExecutionObserved")),
          Map.entry("anomaly", List.of("SpacecraftStateProjected")),
          Map.entry(
              "acquisition",
              List.of(
                  "ScheduleVersionCommitted",
                  "AcquisitionExecutionConfirmed",
                  "DownlinkReceptionCompleted",
                  "SimulatedPayloadReceived")),
          Map.entry("product", List.of("AcquisitionDataComplete")),
          Map.entry("reference-data", List.of("ObservationIntentReceived")),
          Map.entry("mission-projection", List.of("#")));

  @Bean
  Declarables eventQueues() {
    var declarations = new ArrayList<Declarable>();
    var exchange = new TopicExchange(EXCHANGE, true, false);
    declarations.add(exchange);
    var dead = new DirectExchange("msc.dead.v1", true, false);
    declarations.add(dead);
    ROUTES.forEach(
        (name, keys) -> {
          var queue =
              QueueBuilder.durable("msc." + name + ".v1")
                  .deadLetterExchange("msc.dead.v1")
                  .deadLetterRoutingKey(name)
                  .build();
          var dlq = QueueBuilder.durable("msc." + name + ".dead.v1").build();
          declarations.add(queue);
          declarations.add(dlq);
          declarations.add(BindingBuilder.bind(dlq).to(dead).with(name));
          keys.forEach(key -> declarations.add(BindingBuilder.bind(queue).to(exchange).with(key)));
        });
    return new Declarables(declarations);
  }
}
