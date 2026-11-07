package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import msc.domain.shared.Ids.CommandLoadId;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.junit.jupiter.api.Test;

class SimulationSpacecraftLinkTest {
  @Test
  void loadOtherThanTheReleasedSubmissionIsNeverPosted() {
    var json = new CommandCompilerTest().json;
    var compiler = new CommandCompiler(json);
    var released = compiler.compile(new CommandLoadId("a"), CommandCompilerTest.fixture(true)).load();
    var other = compiler.compile(new CommandLoadId("b"), CommandCompilerTest.fixture(true)).load();
    var http = mock(ServiceHttp.class);
    var link =
        new SimulationSpacecraftLink(
            http, json, () -> MissionInstant.tai(1), "a", UUID.randomUUID(),
            json.tree(Map.of("load", released)));
    assertThrows(IllegalArgumentException.class, () -> link.transmit(other));
    verifyNoInteractions(http);
  }
}
