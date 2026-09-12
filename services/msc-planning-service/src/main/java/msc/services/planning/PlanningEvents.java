package msc.services.planning;

import msc.contracts.TaskingContracts.*;
import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public final class PlanningEvents implements EventHandler {
  private final PlanningIntake intake;
  private final Json json;

  public PlanningEvents(PlanningIntake intake, Json json) {
    this.intake = intake;
    this.json = json;
  }

  public void handle(ServiceEvent event) {
    switch (event.type()) {
      case "ObservationRequestAccepted" -> {
        var accepted = json.convert(event.payload(), AcceptedRequest.class);
        if (!event.aggregateId().equals(accepted.requestId())
            || event.aggregateVersion() != accepted.revision())
          throw ApiException.invalid("Accepted request envelope mismatch");
        intake.accepted(accepted, event);
      }
      case "ObservationRequestInvalidated" -> {
        var invalid = json.convert(event.payload(), RequestProgress.class);
        if (!event.aggregateId().equals(invalid.requestId())
            || event.aggregateVersion() <= invalid.revision())
          throw ApiException.invalid("Invalidation envelope mismatch");
        intake.invalidate(invalid, event);
      }
      default -> intake.changedInputs();
    }
  }
}
