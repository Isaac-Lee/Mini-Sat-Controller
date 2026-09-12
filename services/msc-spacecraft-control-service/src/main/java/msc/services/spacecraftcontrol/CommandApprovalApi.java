package msc.services.spacecraftcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.spacecraftcontrol.CommandReleasePolicy.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Human approvals bind immutable prepared content; they never release or dispatch a load. */
@RestController
public class CommandApprovalApi {
  public record Grant(String checksum, MissionInstant validUntil, long expectedVersion) {
    public Grant {
      msc.domain.shared.Checks.text(checksum);
      if (expectedVersion < 0) throw new IllegalArgumentException("Nonnegative version required");
      Objects.requireNonNull(validUntil).requireTai();
    }
  }

  public record Revoke(long expectedVersion) {
    public Revoke {
      if (expectedVersion <= 0) throw new IllegalArgumentException("Positive version required");
    }
  }

  private final StateStore store;
  private final Clock clock;

  public CommandApprovalApi(StateStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  static String kind(String loadId) { return "command-human-approval:" + loadId; }

  @PostMapping("/api/command-loads/{id}/approvals")
  @PreAuthorize("hasRole('OPERATOR')")
  public JsonNode grant(@PathVariable String id, @RequestBody Grant request,
      @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent("command-approval:" + id + ":" + actor.getName(), key, request, () -> {
      store.lock("command-approvals:" + id);
      var load = store.require("prepared-command-load", id, CommandCompiler.Prepared.class).body().load();
      var now = clock.now();
      if (!load.checksum().equals(request.checksum()))
        throw ApiException.conflict("Approval checksum differs from prepared content");
      if (now.compareTo(load.commitDeadline()) >= 0 || now.compareTo(request.validUntil()) >= 0
          || request.validUntil().compareTo(load.commitDeadline()) > 0)
        throw ApiException.invalid("Approval must expire after now and no later than commit deadline");
      var current = store.find(kind(id), actor.getName(), Approval.class);
      if (current.map(StateStore.State::version).orElse(0L) != request.expectedVersion())
        throw ApiException.conflict("Approval version changed");
      if (current.isEmpty() && store.list(kind(id), 100).size() >= 100)
        throw ApiException.conflict("Approval ledger capacity reached");
      var approval = new Approval(Binding.of(load), ApprovalKind.HUMAN, actor.getName(),
          now, request.validUntil(), false);
      var saved = current.isEmpty()
          ? store.create(kind(id), actor.getName(), approval)
          : store.update(kind(id), actor.getName(), request.expectedVersion(), approval);
      store.event("CommandHumanApprovalGranted", id, saved.version(), UUID.randomUUID(), null, saved);
      return saved;
    });
  }

  @PostMapping("/api/command-loads/{id}/approvals/revoke")
  @PreAuthorize("hasRole('OPERATOR')")
  public JsonNode revoke(@PathVariable String id, @RequestBody Revoke request,
      @RequestHeader("Idempotency-Key") String key, Authentication actor) {
    return store.idempotent("command-approval-revoke:" + id + ":" + actor.getName(), key, request, () -> {
      store.lock("command-approvals:" + id);
      var current = store.require(kind(id), actor.getName(), Approval.class);
      if (current.version() != request.expectedVersion())
        throw ApiException.conflict("Approval version changed");
      var prior = current.body();
      if (prior.revoked()) throw ApiException.conflict("Approval already revoked");
      var revoked = new Approval(prior.binding(), prior.kind(), prior.actorId(),
          prior.grantedAt(), prior.validUntil(), true);
      var saved = store.update(kind(id), actor.getName(), current.version(), revoked);
      store.event("CommandHumanApprovalRevoked", id, saved.version(), UUID.randomUUID(), null, saved);
      return saved;
    });
  }

  @GetMapping({"/api/command-loads/{id}/approvals", "/internal/command-loads/{id}/approvals"})
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
  public List<StateStore.State<JsonNode>> read(@PathVariable String id) {
    store.require("prepared-command-load", id, CommandCompiler.Prepared.class);
    return store.list(kind(id), 100);
  }
}
