package msc.services.spacecraftcontrol;

import java.util.*;
import msc.domain.missiondefinition.AuthorityPolicy.Requirement;
import msc.domain.spacecraftcontrol.CommandReleasePolicy;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Checks immutable catalog minimum approvals only; current mission authority still applies. */
@RestController
public class CommandCatalogApprovalApi {
  public record Check(Binding binding, MissionInstant evaluatedAt, Set<Requirement> requirements,
      List<StateStore.State<Approval>> approvals, Set<Reason> reasons) {
    public Check {
      requirements = msc.domain.shared.Checks.orderedEnumSet(Requirement.class, requirements);
      approvals = List.copyOf(approvals);
      reasons = msc.domain.shared.Checks.orderedEnumSet(Reason.class, reasons);
    }
  }

  private final StateStore store;
  private final Json json;
  private final Clock clock;

  public CommandCatalogApprovalApi(StateStore store, Json json, Clock clock) {
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  @PostMapping({"/api/command-loads/{id}/catalog-approval-check", "/internal/command-loads/{id}/catalog-approval-check"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public Check check(@PathVariable String id) {
    return store.transaction(() -> {
      store.lock("command-approvals:" + id);
      var prepared = store.require("prepared-command-load", id, CommandCompiler.Prepared.class).body();
      var required = EnumSet.noneOf(Requirement.class);
      prepared.sources().catalogsByActivity().values().forEach(c -> required.add(c.authority()));
      var approvals = store.list(CommandApprovalApi.kind(id), 100).stream()
          .map(s -> new StateStore.State<>(s.id(), s.version(), json.convert(s.body(), Approval.class))).toList();
      var now = clock.now();
      var binding = Binding.of(prepared.load());
      var reasons = EnumSet.noneOf(Reason.class);
      reasons.addAll(CommandReleasePolicy.approvalReasons(binding, required,
          approvals.stream().map(StateStore.State::body).toList(), now));
      if (now.compareTo(prepared.load().commitDeadline()) >= 0) reasons.add(Reason.DEADLINE_PASSED);
      return new Check(binding, now, required, approvals, reasons);
    });
  }
}
