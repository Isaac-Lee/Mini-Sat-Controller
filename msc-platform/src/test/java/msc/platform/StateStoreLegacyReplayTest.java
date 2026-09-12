package msc.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.ActivityDefinition;
import msc.domain.missiondefinition.AuthorityPolicy;
import msc.domain.shared.Ids.ActivityDefinitionId;
import msc.domain.shared.Ids.ResourceId;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests of {@link StateStore#legacyReplay}, the opt-in rescue for idempotency rows
 * fingerprinted before Set fields (in ActivityDefinition/CatalogContracts) had a canonical,
 * JVM-stable order (see msc.domain.missiondefinition.ActivityDefinitionOrderTest for that defect).
 *
 * <p>legacyReplay only reads {@code this.json}; db/tx/clock are unused by it, so this constructs a
 * StateStore with those null and calls the (package-private, test-only-visible) method directly --
 * no database, no Spring context. A real per-JVM Set.copyOf salt cannot be reproduced within one
 * JVM (two requests in the same process do not exercise it), so instead of relying on incidental
 * agreement, these tests manually rewrite a JSON array's element order inside a JsonNode tree to
 * stand in for "what a different replica's JVM would have serialized" -- exactly the situation
 * StateStore.legacyReplay must reconstruct through, verified against Json.canonical's actual,
 * array-order-preserving behavior on the real domain ActivityDefinition type. Catalog-specific HTTP
 * replay is tested in the mission-definition service, keeping the platform independent of service
 * contract modules.
 */
class StateStoreLegacyReplayTest {
  private final Json json = new Json(new ObjectMapper());
  private final StateStore store = new StateStore(null, null, json, null);

  private ActivityDefinition entry(Set<ResourceId> exclusiveResources, long version) {
    return new ActivityDefinition(
        new ActivityDefinitionId("imaging"),
        version,
        "IMAGING_STRIP",
        true,
        exclusiveResources,
        Set.of(MissionPhase.ROUTINE),
        Set.of("NOMINAL"),
        AuthorityPolicy.RiskClass.LOW,
        "imaging:1");
  }

  private ActivityDefinition baseline() {
    var resources = new LinkedHashSet<ResourceId>();
    resources.add(new ResourceId("antenna"));
    resources.add(new ResourceId("payload"));
    return entry(resources, 1);
  }

  /** State<T>'s "body" wrapper shape, exactly as StateStore.idempotent stores it. */
  private JsonNode wrap(JsonNode body) {
    return json.tree(new StateStore.State<>("imaging:1", 1L, body));
  }

  /** Simulates a different replica's Set.copyOf order by reversing a stored JSON array in place. */
  private static void reverseArrayField(JsonNode root, String... path) {
    JsonNode parent = root;
    for (int i = 0; i < path.length - 1; i++) parent = parent.get(path[i]);
    var array = (ArrayNode) parent.get(path[path.length - 1]);
    var items = new ArrayList<JsonNode>();
    array.forEach(items::add);
    Collections.reverse(items);
    array.removeAll();
    items.forEach(array::add);
  }

  @Test
  void rescuesALegacyRowWhoseArrayOrderDiffersButIsOtherwiseTheSameRequest() {
    var entry = baseline();
    var legacyBody = json.tree(entry).deepCopy();
    reverseArrayField(legacyBody, "exclusiveResources");
    String legacyFingerprint = json.fingerprint(legacyBody);
    var storedResponse = wrap(legacyBody);

    // Sanity: this really is a mismatch against a freshly (canonically) fingerprinted request --
    // otherwise this test would not be exercising the rescue path at all.
    String requestFingerprint = json.fingerprint(entry);
    assertNotEquals(legacyFingerprint, requestFingerprint);

    var rescued =
        store.legacyReplay(
            legacyFingerprint,
            storedResponse,
            requestFingerprint,
            body -> json.convert(body, ActivityDefinition.class));

    assertTrue(rescued.isPresent());
    // The ORIGINAL stored response is returned verbatim -- not a re-canonicalized copy: the
    // rescued body still carries the reversed (legacy) array order, not the canonical one.
    assertEquals(storedResponse, rescued.get());
    assertEquals(legacyBody, rescued.get().get("body"));
    assertEquals(
        "payload",
        rescued.get().get("body").get("exclusiveResources").get(0).get("value").asText());
  }

  @Test
  void doesNotRescueWhenTheStoredFingerprintDoesNotMatchItsOwnStoredBody() {
    var entry = baseline();
    var body = json.tree(entry);
    var storedResponse = wrap(body);
    // Corrupted/forged row: fingerprint does not match response.body. Fail-closed means this
    // proves nothing about the incoming request -- never a route to acceptance.
    String corruptedFingerprint = json.fingerprint(body) + "corrupted";

    var rescued =
        store.legacyReplay(
            corruptedFingerprint,
            storedResponse,
            json.fingerprint(entry),
            reqBody -> json.convert(reqBody, ActivityDefinition.class));

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueOnAGenuineScalarChange() {
    var stored = baseline();
    var storedBody = json.tree(stored);
    var storedResponse = wrap(storedBody);
    String storedFingerprint = json.fingerprint(storedBody);

    var changed = entry(stored.exclusiveResources(), 25); // different definition version
    String requestFingerprint = json.fingerprint(changed);

    var rescued =
        store.legacyReplay(
            storedFingerprint,
            storedResponse,
            requestFingerprint,
            body -> json.convert(body, ActivityDefinition.class));

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueOnAGenuineSetMembershipChange() {
    var stored = baseline();
    var storedBody = json.tree(stored);
    var storedResponse = wrap(storedBody);
    String storedFingerprint = json.fingerprint(storedBody);

    var widerResources = new LinkedHashSet<ResourceId>(stored.exclusiveResources());
    widerResources.add(new ResourceId("wheel"));
    var changed = entry(widerResources, 1);
    String requestFingerprint = json.fingerprint(changed);

    var rescued =
        store.legacyReplay(
            storedFingerprint,
            storedResponse,
            requestFingerprint,
            body -> json.convert(body, ActivityDefinition.class));

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueOnAGenuinelyOrderedListChange() {
    // A synthetic DTO with a genuinely-ordered List field (not a Set): canonicalization must never
    // treat list-order changes as equivalent -- that would be the rejected "sort every array"
    // approach, which Json.canonical deliberately does not do.
    record Ordered(List<String> steps) {}
    var stored = new Ordered(List.of("acquire", "settle", "expose"));
    var storedBody = json.tree(stored);
    var storedResponse = wrap(storedBody);
    String storedFingerprint = json.fingerprint(storedBody);

    var reordered = new Ordered(List.of("settle", "acquire", "expose"));
    String requestFingerprint = json.fingerprint(reordered);
    assertNotEquals(storedFingerprint, requestFingerprint);

    var rescued =
        store.legacyReplay(
            storedFingerprint,
            storedResponse,
            requestFingerprint,
            body -> json.convert(body, Ordered.class));

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueWhenTheDecoderFails() {
    var entry = baseline();
    var body = json.tree(entry);
    var storedResponse = wrap(body);
    String storedFingerprint = json.fingerprint(body);

    var rescued =
        store.legacyReplay(
            storedFingerprint,
            storedResponse,
            json.fingerprint(entry),
            failingBody -> {
              throw new IllegalStateException("decode failure must not widen acceptance");
            });

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueWhenTheDecoderReturnsNull() {
    var entry = baseline();
    var body = json.tree(entry);
    var storedResponse = wrap(body);
    String storedFingerprint = json.fingerprint(body);

    var rescued =
        store.legacyReplay(storedFingerprint, storedResponse, json.fingerprint(entry), b -> null);

    assertTrue(rescued.isEmpty());
  }

  @Test
  void doesNotRescueWhenTheStoredResponseHasNoBodyField() {
    var storedResponse = json.tree(Map.of("id", "imaging:1", "version", 1));
    var rescued = store.legacyReplay("any-fingerprint", storedResponse, "any-fingerprint", b -> b);
    assertTrue(rescued.isEmpty());
  }
}
