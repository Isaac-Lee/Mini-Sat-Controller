package msc.services.flightdynamics;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.domain.time.MissionInstant;
import msc.orbit.*;
import msc.platform.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Derived traditional TLE export; retains the higher-precision public GP source and provenance. */
@RestController
public class PublicTleApi {
  public record Export(
      int noradId,
      String name,
      String snapshotId,
      String sourceRawSha256,
      String sourceUrl,
      MissionInstant fetchedAt,
      String epochUtc,
      String line1,
      String line2,
      String representation) {}

  private final ServiceHttp http;
  private final StateStore store;
  private final GeneralPerturbationsAdapter adapter;

  public PublicTleApi(ServiceHttp http, StateStore store, OrekitReferenceFrames frames) {
    this.http = http;
    this.store = store;
    adapter = new GeneralPerturbationsAdapter(frames);
  }

  @GetMapping("/api/tracked-satellites/{noradId}/tle")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','REQUESTER','SERVICE')")
  public Export latest(@PathVariable int noradId) {
    if (noradId < 1 || noradId > 99999)
      throw ApiException.invalid("Traditional TLE requires NORAD ID 1..99999");
    var snapshot =
        http.get(
            "reference-data", "/internal/tracked-satellites/" + noradId + "/orbit", Snapshot.class);
    if (snapshot.elements().noradId() != noradId)
      throw ApiException.invalid("NORAD snapshot mismatch");
    return export(snapshot);
  }

  @GetMapping("/api/public-orbits/{id}/tle")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','REQUESTER','SERVICE')")
  public Export stored(@PathVariable String id) {
    return export(store.require("public-orbit", id, Snapshot.class).body());
  }

  @GetMapping(
      value = "/api/tracked-satellites/{noradId}/tle.txt",
      produces = MediaType.TEXT_PLAIN_VALUE)
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','REQUESTER','SERVICE')")
  public ResponseEntity<String> text(@PathVariable int noradId) {
    var value = latest(noradId);
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .header("X-MSC-Orbit-Source", "PUBLIC_GP_DERIVED_TLE")
        .header("X-MSC-Snapshot-Id", value.snapshotId())
        .body(value.line1() + "\n" + value.line2() + "\n");
  }

  Export export(Snapshot snapshot) {
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(snapshot.rawJson().getBytes(StandardCharsets.UTF_8)));
      if (!hash.equals(snapshot.rawSha256())
          || !snapshot.id().equals("gp-" + snapshot.elements().noradId() + "-" + hash))
        throw ApiException.invalid("GP snapshot provenance mismatch");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    var lines = adapter.exportTle(snapshot.elements());
    return new Export(
        snapshot.elements().noradId(),
        snapshot.elements().name(),
        snapshot.id(),
        snapshot.rawSha256(),
        snapshot.sourceUrl(),
        snapshot.fetchedAt(),
        snapshot.elements().epochUtc(),
        lines.line1(),
        lines.line2(),
        "DERIVED_FROM_GP_WITH_TLE_PRECISION_ROUNDING_NOT_TELEMETRY");
  }
}
