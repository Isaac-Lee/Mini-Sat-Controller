package msc.services.tasking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.contracts.TaskingContracts.*;
import msc.platform.*;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class RequestApi {
  private final StateStore store;
  private final RequestWorkflow workflow;
  private final JdbcTemplate db;
  private final Json json;
  private final Clock clock;

  public RequestApi(
      StateStore store, RequestWorkflow workflow, JdbcTemplate db, Json json, Clock clock) {
    this.store = store;
    this.workflow = workflow;
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  private boolean elevated(Authentication actor) {
    return actor.getAuthorities().stream()
        .anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_OPERATOR").contains(a.getAuthority()));
  }

  private StateStore.State<RequestDetails> owned(String id, Authentication actor) {
    var current = store.require("request", id, RequestDetails.class);
    if (!current.body().owner().equals(actor.getName()) && !elevated(actor))
      throw ApiException.missing("Request not found");
    return current;
  }

  @PostMapping("/api/requests")
  @PreAuthorize("hasAnyRole('REQUESTER','ADMIN')")
  public JsonNode create(
      @RequestBody Submission submission,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "request-create:" + actor.getName(),
        key,
        submission,
        () -> workflow.submit(UUID.randomUUID().toString(), actor.getName(), submission, null));
  }

  @GetMapping("/api/requests/{id}")
  public StateStore.State<RequestDetails> get(@PathVariable String id, Authentication actor) {
    return owned(id, actor);
  }

  @GetMapping("/internal/requests/{id}")
  public RequestDetails internal(@PathVariable String id) {
    return store.require("request", id, RequestDetails.class).body();
  }

  @GetMapping("/api/requests/{id}/submissions/{revision}")
  public Submission submitted(
      @PathVariable String id, @PathVariable long revision, Authentication actor) {
    owned(id, actor);
    return original(id, revision);
  }

  @GetMapping("/internal/requests/{id}/submissions/{revision}")
  public Submission original(@PathVariable String id, @PathVariable long revision) {
    if (revision < 1) throw ApiException.invalid("Revision must be positive");
    return store.require("request-input", id + ":" + revision, Submission.class).body();
  }

  public record Page(List<StateStore.State<RequestDetails>> items, Optional<String> nextCursor) {}

  @GetMapping("/api/requests")
  public Page list(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "") String after,
      Authentication actor) {
    if (limit < 1 || limit > 100) throw ApiException.invalid("Limit must be 1..100");
    var rows =
        db.query(
            "SELECT id,version,body::text FROM state_head WHERE kind='request' AND"
                + " (body->>'owner'=? OR ?) AND id>? ORDER BY id LIMIT ?",
            (rs, n) ->
                new StateStore.State<>(
                    rs.getString(1),
                    rs.getLong(2),
                    json.read(rs.getString(3), RequestDetails.class)),
            actor.getName(),
            elevated(actor),
            after,
            limit + 1);
    boolean more = rows.size() > limit;
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    return new Page(items, more ? Optional.of(items.getLast().id()) : Optional.empty());
  }

  public record Revision(long expectedVersion, Submission submission) {
    public Revision {
      if (expectedVersion < 1)
        throw new IllegalArgumentException("Expected version must be positive");
      Objects.requireNonNull(submission);
    }
  }

  @PutMapping("/api/requests/{id}")
  public JsonNode revise(
      @PathVariable String id,
      @RequestBody Revision revision,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "request-revise:" + actor.getName(),
        key,
        Map.of("id", id, "revision", revision),
        () -> {
          store.lock("request:" + id);
          var current = owned(id, actor);
          if (current.version() != revision.expectedVersion())
            throw ApiException.conflict("Request changed");
          return workflow.submit(id, current.body().owner(), revision.submission(), current);
        });
  }

  public record Cancel(long expectedVersion) {
    public Cancel {
      if (expectedVersion < 1)
        throw new IllegalArgumentException("Expected version must be positive");
    }
  }

  @PostMapping("/api/requests/{id}/cancel")
  public JsonNode cancel(
      @PathVariable String id,
      @RequestBody Cancel command,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "request-cancel:" + actor.getName(),
        key,
        Map.of("id", id, "command", command),
        () -> {
          store.lock("request:" + id);
          var current = owned(id, actor);
          if (current.version() != command.expectedVersion())
            throw ApiException.conflict("Request changed");
          return workflow.cancel(current);
        });
  }

  @Scheduled(fixedDelayString = "${msc.tasking.expiry-delay-ms:1000}")
  public void expireRequests() {
    var now = clock.now();
    var ids =
        db.queryForList(
            "SELECT id FROM state_head WHERE kind='request' AND body->'request'->>'status' NOT IN"
                + " ('FULFILLED','REJECTED','EXPIRED','CANCELLED') AND"
                + " ((body->'request'->'deadline'->>'seconds')::bigint < ? OR"
                + " ((body->'request'->'deadline'->>'seconds')::bigint = ? AND"
                + " (body->'request'->'deadline'->>'nanos')::int <= ?)) ORDER BY id LIMIT 100",
            String.class,
            now.seconds(),
            now.seconds(),
            now.nanos());
    for (var id : ids)
      store.transaction(
          () -> {
            store.lock("request:" + id);
            var current = store.require("request", id, RequestDetails.class);
            var request = current.body().request();
            if (!request.terminal()
                && request.deadline().isPresent()
                && clock.now().compareTo(request.deadline().get()) >= 0) workflow.expire(current);
            return null;
          });
  }
}
