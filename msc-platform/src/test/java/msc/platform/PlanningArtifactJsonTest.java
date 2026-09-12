package msc.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.domain.anomaly.MissionPhase;
import msc.domain.missiondefinition.*;
import msc.domain.planning.*;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import org.junit.jupiter.api.Test;

class PlanningArtifactJsonTest {
  @Test
  void explicitRunSnapshotSerializesCandidatesAndRestoresExactInputReferences() {
    var json = new Json(JsonMapper.builder().findAndAddModules().build());
    var definition =
        new ActivityDefinition(
            new ActivityDefinitionId("image"),
            1,
            "IMAGE",
            true,
            Set.of(new ResourceId("payload")),
            Set.of(MissionPhase.ROUTINE),
            Set.of("NOMINAL"),
            AuthorityPolicy.RiskClass.LOW,
            "template:1");
    var candidate =
        PlanCandidate.propose(
            new CandidateId("candidate"),
            new PlanningRunId("run"),
            new RequestId("request"),
            new SpacecraftId("craft"),
            new ActivityId("activity"),
            new TimeWindow(MissionInstant.tai(10), MissionInstant.tai(20)),
            definition,
            MissionPhase.ROUTINE,
            "NOMINAL",
            new FeasibilityEvaluation(FeasibilityEvaluation.Status.NOT_EVALUATED, "pending"));
    var refs =
        new EnumMap<PlanningDataSnapshot.Input, SnapshotRef>(PlanningDataSnapshot.Input.class);
    for (var input : PlanningDataSnapshot.Input.values())
      refs.put(
          input,
          new SnapshotRef(
              new SnapshotId(input.name()), 7, "synthetic-reference", MissionInstant.tai(0)));
    var run =
        new PlanningRun(
            candidate.runId(),
            candidate.requestId(),
            MissionInstant.tai(0),
            new PlanningDataSnapshot(refs),
            List.of(),
            List.of(candidate),
            List.of());
    var encoded = json.write(run.snapshot());
    var raw = json.read(encoded, com.fasterxml.jackson.databind.JsonNode.class);
    assertEquals("candidate", raw.path("candidates").get(0).path("id").path("value").asText());
    assertEquals(
        "NOT_EVALUATED", raw.path("candidates").get(0).path("feasibility").path("status").asText());
    var decoded = json.read(encoded, PlanningRun.Snapshot.class);
    var restored = PlanningRun.restore(decoded, (id, version) -> definition);
    assertEquals(run.snapshot(), restored.snapshot());
  }
}
