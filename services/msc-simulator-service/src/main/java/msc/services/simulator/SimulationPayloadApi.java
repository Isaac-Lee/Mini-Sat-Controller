package msc.services.simulator;

import java.io.*;
import java.security.*;
import java.util.*;
import msc.platform.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Onboard object materialization. No ground-reception or fulfillment assertion. */
@RestController
@ConditionalOnProperty(name = "msc.s3.enabled", havingValue = "true")
public class SimulationPayloadApi {
  record Claim(String id, UUID token) {}

  private final StateStore store;
  private final Json json;
  private final JdbcTemplate db;
  private final ObjectStorage objects;

  public SimulationPayloadApi(StateStore store, Json json, JdbcTemplate db, ObjectStorage objects) {
    this.store = store;
    this.json = json;
    this.db = db;
    this.objects = objects;
  }

  Optional<Claim> claim() {
    return store.transaction(
        () -> {
          db.update(
              "INSERT INTO simulation_payload_work(intent_id) SELECT s.id FROM state_head s WHERE"
                  + " s.kind=? AND NOT EXISTS (SELECT 1 FROM simulation_payload_work w WHERE"
                  + " w.intent_id=s.id) ORDER BY s.id LIMIT 100 ON CONFLICT DO NOTHING",
              SimulationPayload.KIND);
          var rows =
              db.query(
                  "SELECT intent_id FROM simulation_payload_work WHERE (status IN"
                      + " ('QUEUED','RETRY') AND next_attempt_at<=now()) OR (status='WRITING' AND"
                      + " lease_until<=now()) ORDER BY next_attempt_at,intent_id FOR UPDATE SKIP"
                      + " LOCKED LIMIT 1",
                  (rs, n) -> new Claim(rs.getString(1), UUID.randomUUID()));
          if (rows.isEmpty()) return Optional.empty();
          var claim = rows.getFirst();
          db.update(
              "UPDATE simulation_payload_work SET"
                  + " status='WRITING',lease_token=?,lease_until=now()+interval '5"
                  + " minutes',attempts=attempts+1 WHERE intent_id=?",
              claim.token(),
              claim.id());
          return Optional.of(claim);
        });
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 2000)
  public void work() {
    claim().ifPresent(this::materialize);
  }

  void materialize(Claim claim) {
    try {
      var source =
          store.require(SimulationPayload.KIND, claim.id(), SimulationPayload.Intent.class).body();
      long bytes = SimulationPayload.bytes(source);
      String sourceHash = json.fingerprint(source);
      var digest = MessageDigest.getInstance("SHA-256");
      String reference;
      try (var content =
          new DigestInputStream(SimulationPayload.samples(sourceHash, bytes), digest)) {
        reference = objects.write("application/octet-stream", content);
      }
      var manifest =
          new SimulationPayload.Manifest(
              claim.id(),
              source,
              sourceHash,
              bytes,
              HexFormat.of().formatHex(digest.digest()),
              reference,
              "SIMULATION",
              "ONBOARD");
      store.transaction(
          () -> {
            var live =
                db.queryForList(
                    "SELECT intent_id FROM simulation_payload_work WHERE intent_id=? AND"
                        + " lease_token=? AND status='WRITING' AND lease_until>now() FOR UPDATE",
                    String.class,
                    claim.id(),
                    claim.token());
            if (live.isEmpty()) return null;
            store.create("simulation-payload", claim.id(), manifest);
            db.update(
                "UPDATE simulation_payload_work SET"
                    + " status='STORED',lease_token=NULL,lease_until=NULL,last_issue=NULL WHERE"
                    + " intent_id=?",
                claim.id());
            return null;
          });
    } catch (ApiException invalid) {
      failure(claim, "REJECTED", invalid.code());
    } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) {
      failure(claim, "RETRY", "PAYLOAD_STORAGE_UNAVAILABLE");
    }
  }

  private void failure(Claim claim, String status, String issue) {
    db.update(
        "UPDATE simulation_payload_work SET"
            + " status=?,last_issue=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval"
            + " '30 seconds' WHERE intent_id=? AND lease_token=? AND status='WRITING' AND"
            + " lease_until>now()",
        status,
        issue,
        claim.id(),
        claim.token());
  }

  @GetMapping("/internal/simulation/payloads/{id}/content")
  @PreAuthorize("hasRole('SERVICE')")
  public org.springframework.http.ResponseEntity<
          org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      content(@PathVariable String id) {
    var manifest = read(id).body();
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(manifest.byteCount())
        .header("X-MSC-Environment", "SIMULATION")
        .header("X-MSC-Location", "ONBOARD")
        .body(
            output -> {
              try (var input = objects.read(manifest.objectReference())) {
                input.transferTo(output);
              }
            });
  }

  @GetMapping("/internal/simulation/payloads/{id}")
  @PreAuthorize("hasRole('SERVICE')")
  public StateStore.State<SimulationPayload.Manifest> read(@PathVariable String id) {
    return store.require("simulation-payload", id, SimulationPayload.Manifest.class);
  }
}
