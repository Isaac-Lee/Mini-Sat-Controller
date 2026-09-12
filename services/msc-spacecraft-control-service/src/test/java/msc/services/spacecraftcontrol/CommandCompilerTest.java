package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import msc.contracts.CatalogContracts.*;
import msc.contracts.SimulationTimeCorrelationContracts.Correlation;
import msc.domain.missiondefinition.*;
import msc.domain.planning.*;
import msc.domain.shared.Ids.*;
import msc.domain.time.*;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class CommandCompilerTest {
  static CommandCompiler.Sources fixture() {
    var catalog =
        new CatalogEntry(
            "image",
            1,
            new ActivityDefinition(
                new ActivityDefinitionId("image-def"),
                1,
                "image",
                true,
                Set.of(new ResourceId("payload")),
                Set.of(),
                Set.of(),
                AuthorityPolicy.RiskClass.LOW,
                "template:1"),
            new CommandTemplate(
                "template",
                1,
                "IMAGE",
                Map.of("exposure", new ParameterRule(ParameterType.NUMBER, true, 1, 10, Set.of()))),
            new ResourceProfile(10, 1, 0),
            AuthorityPolicy.Requirement.HUMAN_APPROVAL,
            10,
            "test");
    var key =
        new ScheduleKey(
            new SpacecraftId("sat"),
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(1100)));
    var activities = new ArrayList<ScheduledActivity>();
    var assignments = new ArrayList<Assignment>();
    for (int i = 1; i >= 0; i--) {
      String id = "activity-" + i;
      activities.add(
          new ScheduledActivity(
              new ActivityId(id),
              catalog.activity().id(),
              1,
              new TimeWindow(MissionInstant.tai(1010 + i * 10), MissionInstant.tai(1020 + i * 10)),
              catalog.activity().exclusiveResources()));
      assignments.add(
          new Assignment(
              new AssignmentId(id),
              new CandidateId(id),
              new RequestId(id),
              new PlanningRunId(id),
              new ActivityId(id)));
    }
    var snapshot =
        new MissionSchedule.Snapshot(
            key,
            2,
            MissionInstant.tai(1000),
            activities,
            assignments,
            ResourceValidation.notEvaluated());
    var mission = new MissionProfile("sat", "image", 1, "m1", 100, 10, 100, 10, 0, "c1", "test");
    var correlation =
        new Correlation(
            "sat",
            "m1",
            "c1",
            "p1",
            MissionInstant.tai(1000),
            0,
            10,
            new TimeWindow(MissionInstant.tai(1000), MissionInstant.tai(2000)),
            "SIMULATION",
            "test",
            "test");
    return new CommandCompiler.Sources(
        snapshot,
        mission,
        new StateStore.State<>("sat", 2, correlation),
        Map.of("activity-0", catalog, "activity-1", catalog),
        Map.of("activity-0", Map.of("exposure", "3"), "activity-1", Map.of("exposure", "4")),
        MissionInstant.tai(1005));
  }

  final Json json =
      new Json(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  final CommandCompiler compiler = new CommandCompiler(json);

  @Test
  void compilesStableOrderedBoundCommandsWithoutReleaseAuthority() {
    var source = fixture();
    var id = new CommandLoadId("load");
    var prepared = compiler.compile(id, source);
    assertEquals(prepared, compiler.compile(id, source));
    assertEquals(100, prepared.load().commands().get(0).timeTag().ticks());
    assertEquals(200, prepared.load().commands().get(1).timeTag().ticks());
    assertEquals("3", prepared.load().commands().get(0).parameters().get("exposure"));
    assertTrue(prepared.load().authorizationEvidenceReference().isEmpty());
    assertEquals(
        ResourceValidation.Status.NOT_EVALUATED,
        prepared.sources().schedule().resourceValidation().reservoirStatus());
    assertNotEquals(
        prepared.load().checksum(),
        compiler.compile(new CommandLoadId("other"), source).load().checksum());
    assertEquals(
        prepared.sourceSha256(),
        compiler.compile(new CommandLoadId("other"), source).sourceSha256());
  }

  @Test
  void rejectsDurationMismatchUnknownParametersAndWrongCorrelation() {
    var s = fixture();
    var c = s.catalogsByActivity().get("activity-0");
    var longer =
        new CatalogEntry(
            c.id(),
            c.version(),
            c.activity(),
            c.template(),
            c.resources(),
            c.authority(),
            11,
            c.approvalReference());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compiler.compile(
                new CommandLoadId("load"),
                new CommandCompiler.Sources(
                    s.schedule(),
                    s.mission(),
                    s.correlation(),
                    Map.of("activity-0", longer, "activity-1", c),
                    s.parametersByActivity(),
                    s.deadline())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compiler.compile(
                new CommandLoadId("load"),
                new CommandCompiler.Sources(
                    s.schedule(),
                    s.mission(),
                    s.correlation(),
                    s.catalogsByActivity(),
                    Map.of("activity-0", Map.of("exposure", "999"), "activity-1", Map.of()),
                    s.deadline())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compiler.compile(
                new CommandLoadId("load"),
                new CommandCompiler.Sources(
                    s.schedule(),
                    s.mission(),
                    new StateStore.State<>("other", 2, s.correlation().body()),
                    s.catalogsByActivity(),
                    s.parametersByActivity(),
                    s.deadline())));
  }

  @Test
  void rejectsMissingBindingsAndLateDeadline() {
    var s = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compiler.compile(
                new CommandLoadId("load"),
                new CommandCompiler.Sources(
                    s.schedule(),
                    s.mission(),
                    s.correlation(),
                    Map.of(),
                    s.parametersByActivity(),
                    s.deadline())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compiler.compile(
                new CommandLoadId("load"),
                new CommandCompiler.Sources(
                    s.schedule(),
                    s.mission(),
                    s.correlation(),
                    s.catalogsByActivity(),
                    s.parametersByActivity(),
                    MissionInstant.tai(1011))));
  }
}
