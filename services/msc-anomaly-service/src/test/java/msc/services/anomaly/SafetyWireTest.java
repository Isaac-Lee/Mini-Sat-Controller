package msc.services.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.domain.anomaly.*;
import msc.domain.anomaly.SafetyPolicy.Reason;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import msc.platform.Json;
import org.junit.jupiter.api.Test;

class SafetyWireTest {
  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final MissionInstant now = new MissionInstant(1000, 0, TimeScale.TAI);

  @Test
  void checkAndPersistedIncidentUseFixedReasonOrderAcrossReconstruction() {
    var reasons = new LinkedHashSet<>(List.of(Reason.LOW_BATTERY, Reason.SAFE_MODE));
    var latch = new SafetyLatch("sim", 1, 1, true, reasons, List.of());
    var check = new SafetyStore.Check(false, 1, 1, 1, now, reasons, latch);
    var anomaly = new Anomaly(new AnomalyId("incident"), new SpacecraftId("sim"),
        Anomaly.Severity.CRITICAL, now, "synthetic");
    var incident = new SafetyStore.Incident(anomaly, 1, 1, reasons);
    reasons.clear();
    var expected = List.of(Reason.SAFE_MODE, Reason.LOW_BATTERY);
    assertEquals(expected, new ArrayList<>(check.currentReasons()));
    assertEquals(expected, new ArrayList<>(incident.reasons()));
    assertThrows(UnsupportedOperationException.class, () -> check.currentReasons().clear());
    assertThrows(UnsupportedOperationException.class, () -> incident.reasons().clear());
    // Literal wire expectation detects randomized order even when both objects in one JVM agree.
    assertEquals("[\"SAFE_MODE\",\"LOW_BATTERY\"]", json.tree(check).path("currentReasons").toString());
    assertEquals("[\"SAFE_MODE\",\"LOW_BATTERY\"]", json.tree(incident).path("reasons").toString());
    var reversed = json.tree(incident).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) reversed)
        .putArray("reasons").add("LOW_BATTERY").add("SAFE_MODE");
    assertEquals(json.fingerprint(incident),
        json.fingerprint(json.convert(reversed, SafetyStore.Incident.class)));
    assertEquals(check, json.convert(json.tree(check), SafetyStore.Check.class));
  }
}
