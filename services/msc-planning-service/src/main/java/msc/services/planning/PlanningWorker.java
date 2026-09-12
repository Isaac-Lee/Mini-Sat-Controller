package msc.services.planning;

import java.util.*;
import msc.platform.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class PlanningWorker {
  private final PlanningIntake intake;
  private final PlanningInputs inputs;
  private final msc.ports.Clock clock;

  public PlanningWorker(PlanningIntake intake, PlanningInputs inputs, msc.ports.Clock clock) {
    this.intake = intake;
    this.inputs = inputs;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 500, initialDelay = 1000)
  public void work() {
    intake
        .claim()
        .ifPresent(
            claim -> {
              PlanningInputs.Attempt result;
              try {
                result = inputs.collect(claim);
              } catch (RuntimeException failure) {
                result =
                    new PlanningInputs.Attempt(
                        UUID.randomUUID().toString(),
                        claim.requestId(),
                        claim.revision(),
                        clock.now(),
                        "WAITING_INPUTS",
                        Optional.empty(),
                        List.of(),
                        List.of("INPUT_COLLECTION_FAILED"));
              }
              intake.finish(claim, result);
            });
  }
}
