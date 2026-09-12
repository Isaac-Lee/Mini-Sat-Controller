package msc.services.missiondefinition;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.platform.Json;
import org.junit.jupiter.api.Test;

/**
 * Reproduces, without needing two JVMs, the two failure classes from
 * .local/claude-delegation/opus-set-order-review.md using the real production Json/CatalogEntry
 * classes:
 *
 * <p>Class A -- CatalogApi.get's public snapshot must be byte-identical no matter what array order
 * the request body arrived in (simulating two replicas that would, pre-fix, have re-serialized a
 * stored Set field in different orders).
 *
 * <p>Class B -- CatalogApi.create's idempotency fingerprint must likewise be order-independent, so
 * a retry landing on a different replica is not rejected as "reused for different request".
 *
 * <p>This asserts a literal, fixed golden wire order (not merely that two same-process requests
 * happen to agree, which two requests in one JVM cannot prove about a per-JVM salt -- see
 * StateStoreLegacyReplayTest and ActivityDefinitionOrderTest for the same caveat).
 */
class JsonCatalogSetOrderTest {
  private final Json json = new Json(new ObjectMapper());

  private static final String ENTRY_FORWARD_ORDER =
      """
      {"id":"imaging","version":1,
       "activity":{"id":{"value":"imaging"},"version":1,"name":"IMAGING_STRIP","approved":true,
         "exclusiveResources":[{"value":"antenna"},{"value":"bus"},{"value":"payload"}],
         "allowedPhases":["LEOP","ROUTINE","EOL"],
         "allowedModes":["AUTONOMOUS","MANUAL","NOMINAL"],
         "riskClass":"LOW","commandTemplateReference":"imaging:1"},
       "template":{"id":"imaging","version":1,"operation":"IMAGE",
         "parameters":{"quality":{"type":"TEXT","required":false,"minimum":0,"maximum":1,
           "allowedValues":["HIGH","LOW","MEDIUM"]}}},
       "resources":{"powerWatts":20,"generatedMegabytes":1,"propellantKilograms":0},
       "authority":"AUTO_ALLOWED","durationSeconds":10,"approvalReference":"approved-by-test"}
      """;

  // Same logical entry; every Set-backed array is written in a different order. This stands in
  // for a second replica whose Set.copyOf iteration order differed.
  private static final String ENTRY_REVERSED_ORDER =
      """
      {"id":"imaging","version":1,
       "activity":{"id":{"value":"imaging"},"version":1,"name":"IMAGING_STRIP","approved":true,
         "exclusiveResources":[{"value":"payload"},{"value":"bus"},{"value":"antenna"}],
         "allowedPhases":["EOL","ROUTINE","LEOP"],
         "allowedModes":["NOMINAL","MANUAL","AUTONOMOUS"],
         "riskClass":"LOW","commandTemplateReference":"imaging:1"},
       "template":{"id":"imaging","version":1,"operation":"IMAGE",
         "parameters":{"quality":{"type":"TEXT","required":false,"minimum":0,"maximum":1,
           "allowedValues":["MEDIUM","HIGH","LOW"]}}},
       "resources":{"powerWatts":20,"generatedMegabytes":1,"propellantKilograms":0},
       "authority":"AUTO_ALLOWED","durationSeconds":10,"approvalReference":"approved-by-test"}
      """;

  @Test
  void publicSnapshotIsByteIdenticalRegardlessOfIncomingArrayOrder() {
    var forward = json.read(ENTRY_FORWARD_ORDER, CatalogEntry.class);
    var reversed = json.read(ENTRY_REVERSED_ORDER, CatalogEntry.class);

    String forwardWritten = json.write(forward);
    String reversedWritten = json.write(reversed);
    assertEquals(forwardWritten, reversedWritten);

    // Fixed golden order: natural order for the two String-keyed sets, ordinal order for the enum
    // set, regardless of which of the two inputs produced it.
    assertTrue(forwardWritten.contains("\"exclusiveResources\":[{\"value\":\"antenna\"},{\"value\":\"bus\"},{\"value\":\"payload\"}]"));
    assertTrue(forwardWritten.contains("\"allowedPhases\":[\"LEOP\",\"ROUTINE\",\"EOL\"]"));
    assertTrue(forwardWritten.contains("\"allowedModes\":[\"AUTONOMOUS\",\"MANUAL\",\"NOMINAL\"]"));
    assertTrue(forwardWritten.contains("\"allowedValues\":[\"HIGH\",\"LOW\",\"MEDIUM\"]"));
  }

  @Test
  void idempotencyFingerprintIsEqualRegardlessOfIncomingArrayOrder() {
    var forward = json.read(ENTRY_FORWARD_ORDER, CatalogEntry.class);
    var reversed = json.read(ENTRY_REVERSED_ORDER, CatalogEntry.class);
    assertEquals(json.fingerprint(forward), json.fingerprint(reversed));
  }

  @Test
  void aGenuineDifferenceStillProducesADifferentFingerprintAndSnapshot() {
    var forward = json.read(ENTRY_FORWARD_ORDER, CatalogEntry.class);
    var withExtraResource =
        json.read(
            ENTRY_FORWARD_ORDER.replace(
                "\"exclusiveResources\":[{\"value\":\"antenna\"},{\"value\":\"bus\"},{\"value\":\"payload\"}]",
                "\"exclusiveResources\":[{\"value\":\"antenna\"},{\"value\":\"bus\"},{\"value\":\"payload\"},{\"value\":\"wheel\"}]"),
            CatalogEntry.class);
    assertNotEquals(json.fingerprint(forward), json.fingerprint(withExtraResource));
    assertNotEquals(json.write(forward), json.write(withExtraResource));
  }
}
