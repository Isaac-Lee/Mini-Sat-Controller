package msc.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import msc.ports.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Infrastructure JSON snapshots and immutable revisions in this service's database only. */
@Component
public final class StateStore {
  public record State<T>(String id, long version, T body) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Json json;
  private final Clock clock;

  public StateStore(JdbcTemplate db, TransactionTemplate tx, Json json, Clock clock) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  public <T> T transaction(Supplier<T> action) {
    return tx.execute(status -> action.get());
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("State mutation requires transaction");
  }

  public void lock(String key) {
    requireTransaction();
    db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, key);
  }

  public <T> Optional<State<T>> find(String kind, String id, Class<T> type) {
    return db
        .query(
            "SELECT id,version,body::text FROM state_head WHERE kind=? AND id=?",
            (rs, n) ->
                new State<>(rs.getString(1), rs.getLong(2), json.read(rs.getString(3), type)),
            kind,
            id)
        .stream()
        .findFirst();
  }

  public <T> State<T> require(String kind, String id, Class<T> type) {
    return find(kind, id, type).orElseThrow(() -> ApiException.missing(kind + " not found"));
  }

  public List<State<JsonNode>> list(String kind, int limit) {
    if (limit < 1 || limit > 500) throw ApiException.invalid("Limit must be 1..500");
    return db.query(
        "SELECT id,version,body::text FROM state_head WHERE kind=? ORDER BY id LIMIT ?",
        (rs, n) ->
            new State<>(rs.getString(1), rs.getLong(2), json.read(rs.getString(3), JsonNode.class)),
        kind,
        limit);
  }

  public <T> Optional<State<T>> version(String kind, String id, long version, Class<T> type) {
    return db
        .query(
            "SELECT id,version,body::text FROM state_history WHERE kind=? AND id=? AND version=?",
            (rs, n) ->
                new State<>(rs.getString(1), rs.getLong(2), json.read(rs.getString(3), type)),
            kind,
            id,
            version)
        .stream()
        .findFirst();
  }

  public List<State<JsonNode>> history(String kind, String id) {
    return db.query(
        "SELECT id,version,body::text FROM state_history WHERE kind=? AND id=? ORDER BY version",
        (rs, n) ->
            new State<>(rs.getString(1), rs.getLong(2), json.read(rs.getString(3), JsonNode.class)),
        kind,
        id);
  }

  public <T> State<T> create(String kind, String id, T body) {
    requireTransaction();
    var text = json.write(body);
    db.update(
        "INSERT INTO state_head(kind,id,version,body) VALUES(?,?,1,?::jsonb)", kind, id, text);
    db.update(
        "INSERT INTO state_history(kind,id,version,body) VALUES(?,?,1,?::jsonb)", kind, id, text);
    return new State<>(id, 1, body);
  }

  public <T> State<T> update(String kind, String id, long expected, T body) {
    requireTransaction();
    var text = json.write(body);
    long next = Math.addExact(expected, 1);
    int changed =
        db.update(
            "UPDATE state_head SET version=?,body=?::jsonb WHERE kind=? AND id=? AND version=?",
            next,
            text,
            kind,
            id,
            expected);
    if (changed != 1) throw ApiException.conflict("State version changed");
    db.update(
        "INSERT INTO state_history(kind,id,version,body) VALUES(?,?,?,?::jsonb)",
        kind,
        id,
        next,
        text);
    return new State<>(id, next, body);
  }

  public ServiceEvent event(
      String type, String id, long version, UUID correlation, UUID causation, Object payload) {
    requireTransaction();
    var event =
        new ServiceEvent(
            UUID.randomUUID(),
            1,
            type,
            id,
            version,
            correlation,
            causation,
            clock.now(),
            json.tree(payload));
    db.update(
        "INSERT INTO outbox(event_id,event_type,envelope) VALUES(?,?,?::jsonb)",
        event.eventId(),
        type,
        json.write(event));
    return event;
  }

  public boolean receive(UUID id) {
    requireTransaction();
    return db.update("INSERT INTO inbox(event_id) VALUES(?) ON CONFLICT DO NOTHING", id) == 1;
  }

  /** Read a completed immutable response before expensive, retry-safe external computation. */
  public Optional<JsonNode> replay(String scope, String key, Object request) {
    return replay(scope, key, request, null);
  }

  /**
   * Same as {@link #replay(String, String, Object)}, with an optional opt-in legacy-replay
   * rescue for a fingerprint mismatch. {@code legacyBodyDecoder} is null for every strict caller
   * (the default), so their behavior is byte-unchanged. See {@link
   * #legacyReplay(String, JsonNode, String, Function)} for what the rescue does and why it must
   * stay fail-closed.
   */
  public Optional<JsonNode> replay(
      String scope, String key, Object request, Function<JsonNode, Object> legacyBodyDecoder) {
    if (key == null || key.isBlank() || key.length() > 160)
      throw ApiException.invalid("Idempotency-Key required (1..160 chars)");
    var old =
        db.query(
            "SELECT fingerprint,response::text FROM idempotency WHERE scope=? AND request_key=?",
            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
            scope,
            key);
    if (old.isEmpty()) return Optional.empty();
    String storedFingerprint = old.getFirst()[0];
    String requestFingerprint = json.fingerprint(request);
    if (storedFingerprint.equals(requestFingerprint))
      return Optional.of(json.read(old.getFirst()[1], JsonNode.class));
    if (legacyBodyDecoder != null) {
      var storedResponse = json.read(old.getFirst()[1], JsonNode.class);
      var rescued = legacyReplay(storedFingerprint, storedResponse, requestFingerprint, legacyBodyDecoder);
      if (rescued.isPresent()) return rescued;
    }
    throw ApiException.conflict("Idempotency key reused for different request");
  }

  /**
   * Opt-in rescue for idempotency rows written before a request DTO's canonical (JVM-stable) Set
   * ordering existed. Such a row's stored fingerprint was taken over {@code response.body} in the
   * same JVM call that produced it, so {@code fingerprint(response.body) == storedFingerprint} by
   * construction at write time. That equality is exactly what step (a) below re-checks before
   * trusting anything reconstructed from the row.
   *
   * <p>This is a best-effort RESCUE, not a validation: a failure at any step proves nothing about
   * whether the incoming request actually differs from the original one — it only means the proof
   * could not be completed. In particular, step (a) can fail on a perfectly legitimate legacy row
   * because the {@code response} column is {@code jsonb}, which renormalizes numbers (e.g. a
   * double Jackson wrote as {@code "1.0E10"} reads back as integral {@code "10000000000"}), so a
   * canonical re-render of a stored number can legitimately differ from what was hashed at write
   * time. Every failure path here therefore falls through to the caller's strict conflict — never
   * to acceptance. This method never writes anything (no state_head/state_history/outbox/new
   * idempotency row) and never rewrites the stored legacy fingerprint.
   *
   * <p>Package-private (rather than {@code private}) solely so a same-package unit test can
   * exercise this pure decision logic directly, without a database.
   */
  Optional<JsonNode> legacyReplay(
      String storedFingerprint,
      JsonNode storedResponse,
      String requestFingerprint,
      Function<JsonNode, Object> legacyBodyDecoder) {
    try {
      var body = storedResponse.get("body");
      if (body == null) return Optional.empty();
      // (a) Proof that the stored fingerprint was actually taken over this exact body.
      if (!storedFingerprint.equals(json.fingerprint(body))) return Optional.empty();
      // (b) Decode the legacy body under the current (canonical) constructors.
      var reconstructed = legacyBodyDecoder.apply(body);
      if (reconstructed == null) return Optional.empty();
      // (c)/(d)/(e) Canonical fingerprints now agree iff the requests were the same modulo Set
      // order. Equal -> return the ORIGINAL stored response verbatim; different -> no rescue.
      if (!json.fingerprint(reconstructed).equals(requestFingerprint)) return Optional.empty();
      return Optional.of(storedResponse);
    } catch (RuntimeException legacyProofFailed) {
      // Decode/convert failure means the rescue could not be attempted, not that the request
      // differs. Fall through to the caller's strict conflict.
      return Optional.empty();
    }
  }

  public JsonNode idempotent(String scope, String key, Object request, Supplier<?> action) {
    return idempotent(scope, key, request, action, null);
  }

  /**
   * Same as {@link #idempotent(String, String, Object, Supplier)}, with an optional opt-in
   * legacy-replay rescue (see {@link #legacyReplay}) applied only on a fingerprint mismatch.
   * {@code legacyBodyDecoder} is null for every existing caller, so their behavior is
   * byte-unchanged; pass it only from a call site that explicitly opts in (never infer it from
   * the scope string). On a successful rescue, {@code action} is never invoked, so nothing new is
   * written: this reuses the exact same "already completed" path as an ordinary replay hit.
   */
  public JsonNode idempotent(
      String scope,
      String key,
      Object request,
      Supplier<?> action,
      Function<JsonNode, Object> legacyBodyDecoder) {
    if (key == null || key.isBlank() || key.length() > 160)
      throw ApiException.invalid("Idempotency-Key required (1..160 chars)");
    return transaction(
        () -> {
          lock("idempotency:" + scope + ":" + key);
          String hash = json.fingerprint(request);
          var completed = replay(scope, key, request, legacyBodyDecoder);
          if (completed.isPresent()) return completed.get();
          var response = json.tree(action.get());
          db.update(
              "INSERT INTO idempotency(scope,request_key,fingerprint,response)"
                  + " VALUES(?,?,?,?::jsonb)",
              scope,
              key,
              hash,
              json.write(response));
          return response;
        });
  }
}
