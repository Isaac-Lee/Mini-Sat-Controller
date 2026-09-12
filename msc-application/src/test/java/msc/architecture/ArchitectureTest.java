package msc.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.net.URL;
import java.util.List;
import msc.application.PlanObservation;
import msc.domain.planning.MissionSchedule;
import msc.infrastructure.time.VirtualClock;
import msc.ports.Clock;
import org.junit.jupiter.api.Test;

/**
 * Checks compiled production bytecode, including fully qualified references, not source regexes.
 */
class ArchitectureTest {
  private JavaClasses productionClasses() {
    // Maven test uses classes directories; verify can resolve upstream modules as JARs.
    var classes =
        new ClassFileImporter()
            .importUrls(
                List.of(
                    location(MissionSchedule.class), location(Clock.class),
                    location(PlanObservation.class), location(VirtualClock.class)));
    for (var type :
        List.of(MissionSchedule.class, Clock.class, PlanObservation.class, VirtualClock.class)) {
      assertTrue(classes.contain(type), "Missing production module: " + type.getName());
    }
    return classes;
  }

  private URL location(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation();
  }

  @Test
  void domainOnlyUsesDomainAndSmallJdkValueCollectionsSurface() throws Exception {
    classes()
        .that()
        .resideInAPackage("msc.domain..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("msc.domain..", "java.lang..", "java.util..", "java.math..")
        .check(productionClasses());
  }

  @Test
  void taskingAndPlanningRespectContextBoundaries() throws Exception {
    noClasses()
        .that()
        .resideInAPackage("msc.domain.tasking..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("msc.domain.planning..")
        .check(productionClasses());
    noClasses()
        .that()
        .resideInAPackage("msc.domain.planning..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..ccsds..", "..transport..", "msc.infrastructure..", "msc.domain.spacecraftcontrol..")
        .check(productionClasses());
  }

  @Test
  void applicationAndPortsCannotImportAdaptersOrFrameworks() throws Exception {
    classes()
        .that()
        .resideInAnyPackage("msc.application..", "msc.projections..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "msc.application..",
            "msc.projections..",
            "msc.domain..",
            "msc.ports..",
            "java.lang..",
            "java.util..",
            "java.math..")
        .check(productionClasses());
    classes()
        .that()
        .resideInAPackage("msc.ports..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "msc.ports..", "msc.domain..", "java.lang..", "java.util..", "java.io..")
        .check(productionClasses());
  }

  @Test
  void businessBehaviorCannotReadWallClock() throws Exception {
    var rule =
        noClasses()
            .that()
            .resideInAnyPackage("msc.domain..", "msc.application..", "msc.projections..");
    rule.should().callMethod(System.class, "currentTimeMillis").check(productionClasses());
    rule.should().callMethod(System.class, "nanoTime").check(productionClasses());
    rule.should().callConstructor(java.util.Date.class).check(productionClasses());
    rule.should().callMethod(java.util.Calendar.class, "getInstance").check(productionClasses());
  }
}
