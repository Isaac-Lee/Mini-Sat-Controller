package msc.services.planning;

import java.util.*;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlanningApi {
  private final PlanningIntake intake;
  private final PlanningInputs inputs;
  private final StateStore store;

  public PlanningApi(PlanningIntake intake, PlanningInputs inputs, StateStore store) {
    this.intake = intake;
    this.inputs = inputs;
    this.store = store;
  }

  @GetMapping("/api/planning/requests/{id}")
  public Map<String, Object> status(@PathVariable String id, Authentication actor) {
    msc.contracts.TaskingContracts.RequestDetails request;
    try {
      request = inputs.request(id);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
      throw ApiException.missing("Request not found");
    }
    boolean elevated =
        actor.getAuthorities().stream()
            .anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_OPERATOR").contains(a.getAuthority()));
    if (!elevated && !request.owner().equals(actor.getName()))
      throw ApiException.missing("Request not found");
    return intake.status(id);
  }

  @GetMapping("/internal/planning/requests/{id}")
  public Map<String, Object> internal(@PathVariable String id) {
    return intake.status(id);
  }

  @GetMapping({"/api/planning/input-attempts/{id}", "/internal/planning/input-attempts/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public PlanningInputs.Attempt attempt(@PathVariable String id) {
    return store.require("planning-input-attempt", id, PlanningInputs.Attempt.class).body();
  }

  @GetMapping({"/api/planning/runs/{id}", "/internal/planning/runs/{id}"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public PlanningRuns.Published run(@PathVariable String id) {
    return store.require("planning-run", id, PlanningRuns.Published.class).body();
  }

  @GetMapping({"/api/planning/runs/{id}/resources", "/internal/planning/runs/{id}/resources"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public PlanningResources.Assessment resources(@PathVariable String id) {
    return store
        .require("planning-resource-assessment", id, PlanningResources.Assessment.class)
        .body();
  }

  @GetMapping({
    "/api/planning/input-attempts/{id}/runs",
    "/internal/planning/input-attempts/{id}/runs"
  })
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public PlanningRuns.Index runs(@PathVariable String id) {
    store.require("planning-input-attempt", id, PlanningInputs.Attempt.class);
    // Pre-feature attempts are immutable and have no recorded runs.
    return store
        .find("planning-run-index", id, PlanningRuns.Index.class)
        .map(StateStore.State::body)
        .orElseGet(() -> new PlanningRuns.Index(id, List.of()));
  }
}
