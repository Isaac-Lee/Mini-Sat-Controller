package msc.services.flightdynamics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import msc.contracts.OrbitReferenceContracts.Snapshot;
import msc.domain.flightdynamics.MeanElements;
import msc.domain.time.MissionInstant;
import msc.orbit.OrekitReferenceFrames;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class PublicTleApiTest {
  final ServiceHttp http = mock(ServiceHttp.class);
  final StateStore store = mock(StateStore.class);

  PublicTleApi api() throws Exception {
    return new PublicTleApi(
        http,
        store,
        new OrekitReferenceFrames(
            Path.of(System.getenv("MSC_TEST_OREKIT_ARCHIVE")),
            System.getenv("MSC_TEST_OREKIT_SHA256")));
  }

  Snapshot snapshot(int norad) throws Exception {
    var e =
        new MeanElements(
            norad,
            "SPACEEYE-T1",
            "2025-052V",
            "2026-09-11T03:28:43.405248",
            15.23132288,
            .00040966,
            97.3818,
            145.2884,
            187.0783,
            173.0397,
            .00013731904,
            3.172e-5,
            0,
            999,
            8292,
            "U");
    String raw = "[]",
        hash =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return new Snapshot(
        "gp-" + norad + "-" + hash,
        e,
        "test-GP",
        "https://example.invalid/gp",
        MissionInstant.tai(1000),
        hash,
        raw);
  }

  @Test
  void latestExportRetainsProvenanceAndTextHasExactlyTwoLines() throws Exception {
    var snapshot = snapshot(63229);
    when(http.get("reference-data", "/internal/tracked-satellites/63229/orbit", Snapshot.class))
        .thenReturn(snapshot);
    var api = api();
    var value = api.latest(63229);
    assertEquals(snapshot.id(), value.snapshotId());
    assertEquals(snapshot.rawSha256(), value.sourceRawSha256());
    assertTrue(value.representation().contains("ROUNDING_NOT_TELEMETRY"));
    var response = api.text(63229);
    assertEquals(2, response.getBody().lines().count());
    assertEquals(value.line1() + "\n" + value.line2() + "\n", response.getBody());
    assertEquals("PUBLIC_GP_DERIVED_TLE", response.getHeaders().getFirst("X-MSC-Orbit-Source"));
    verifyNoInteractions(store);
  }

  @Test
  void rejectsWrongNoradAndCorruptRawProvenance() throws Exception {
    var snapshot = snapshot(63228);
    when(http.get(anyString(), anyString(), eq(Snapshot.class))).thenReturn(snapshot);
    var api = api();
    assertThrows(ApiException.class, () -> api.latest(63229));
    var valid = snapshot(63229);
    var corrupt =
        new Snapshot(
            valid.id(),
            valid.elements(),
            valid.provider(),
            valid.sourceUrl(),
            valid.fetchedAt(),
            valid.rawSha256(),
            "changed");
    assertThrows(ApiException.class, () -> api.export(corrupt));
    clearInvocations(http);
    assertThrows(ApiException.class, () -> api.latest(100000));
    verifyNoInteractions(http);
  }
}
