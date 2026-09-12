package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.AuthorityContracts.Policy;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.domain.missiondefinition.AuthorityPolicy.Requirement;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.domain.spacecraftcontrol.CommandReleasePolicy;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Approval;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.Reason;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

/** Current SIMULATION authority diagnostic; no owner revision fence or dispatch permission. */
@RestController
public class CommandAuthorityCheckApi {
  public record Check(CommandReleasePolicy.Binding binding, MissionInstant evaluatedAt,
      StateStore.State<Policy> policy, StateStore.State<Model> phaseModel,
      StateStore.State<Estimate> telemetry, Set<Requirement> requirements,
      List<StateStore.State<Approval>> approvals, Set<Reason> reasons) {
    public Check {
      requirements = msc.domain.shared.Checks.orderedEnumSet(Requirement.class, requirements);
      approvals = List.copyOf(approvals);
      reasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, reasons);
    }
  }

  private final StateStore store;
  private final ServiceHttp http;
  private final Json json;
  private final Clock clock;

  public CommandAuthorityCheckApi(StateStore store, ServiceHttp http, Json json, Clock clock) {
    this.store = store;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  private <T> StateStore.State<T> decode(JsonNode envelope, String craft, Class<T> type) {
    if (envelope == null || !craft.equals(envelope.path("id").asText())
        || !envelope.path("version").isIntegralNumber()
        || !envelope.path("version").canConvertToLong()
        || envelope.path("version").asLong() <= 0 || !envelope.hasNonNull("body"))
      throw ApiException.invalid("Owner evidence identity/version mismatch");
    return new StateStore.State<>(craft, envelope.path("version").asLong(), json.convert(envelope.get("body"), type));
  }

  @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class)
  @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
  public ApiErrors.Error missingOwner() { return new ApiErrors.Error("NOT_FOUND", "Required authority owner artifact not found"); }

  @PostMapping({"/api/command-loads/{id}/authority-check", "/internal/command-loads/{id}/authority-check"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public Check check(@PathVariable String id) {
    var prepared = store.require("prepared-command-load", id, CommandCompiler.Prepared.class).body();
    var load = prepared.load();
    String craft = load.scheduleKey().spacecraftId().value();
    String path = UriUtils.encodePathSegment(craft, StandardCharsets.UTF_8);
    var policy = decode(http.get("mission-definition", "/internal/authority-policies/" + path, JsonNode.class), craft, Policy.class);
    var model = decode(http.get("mission-definition", "/internal/simulation-planning-models/" + path, JsonNode.class), craft, Model.class);
    var view = http.get("monitoring", "/internal/spacecraft-estimates/" + path, JsonNode.class);
    var telemetry = decode(view.get("estimate"), craft, Estimate.class);
    if (!craft.equals(policy.body().spacecraftId()) || !craft.equals(model.body().spacecraftId())
        || !load.missionDefinitionVersion().equals(policy.body().missionDefinitionVersion())
        || !load.missionDefinitionVersion().equals(model.body().missionDefinitionVersion())
        || !craft.equals(telemetry.body().binding().spacecraftId())
        || telemetry.body().binding().environment() != Environment.SIMULATION)
      throw ApiException.invalid("Authority/phase/telemetry must bind this simulation mission");
    return store.transaction(() -> {
      store.lock("command-approvals:" + id);
      var now = clock.now();
      var requirements = EnumSet.noneOf(Requirement.class);
      var reasons = EnumSet.noneOf(Reason.class);
      var estimate = telemetry.body();
      boolean fresh = estimate.confidence(now) == Confidence.FRESH;
      if (!fresh) reasons.add(Reason.STALE_CONTEXT);
      for (var catalog : prepared.sources().catalogsByActivity().values()) {
        requirements.add(catalog.authority());
        if (fresh) {
          var frame = estimate.accepted().orElseThrow().frame();
          if (!craft.equals(frame.spacecraftId()) || !estimate.binding().source().equals(frame.source())
              || estimate.binding().version() != frame.bindingVersion())
            throw ApiException.invalid("Accepted telemetry frame binding mismatch");
          String mode = frame.mode().name();
          try { catalog.activity().requireSchedulable(model.body().phase(), mode); }
          catch (IllegalArgumentException invalidMode) { requirements.add(Requirement.AUTO_FORBIDDEN); }
          requirements.add(policy.body().evaluate(catalog.template().operation(), model.body().phase(), mode, catalog.activity().riskClass()));
        }
      }
      if (!fresh) reasons.add(Reason.AUTHORITY_UNKNOWN);
      var approvals = store.list(CommandApprovalApi.kind(id), 100).stream()
          .map(s -> new StateStore.State<>(s.id(), s.version(), json.convert(s.body(), Approval.class))).toList();
      var binding = CommandReleasePolicy.Binding.of(load);
      reasons.addAll(CommandReleasePolicy.approvalReasons(binding, requirements, approvals.stream().map(StateStore.State::body).toList(), now));
      if (now.compareTo(load.commitDeadline()) >= 0) reasons.add(Reason.DEADLINE_PASSED);
      return new Check(binding, now, policy, model, telemetry, requirements, approvals, reasons);
    });
  }
}
