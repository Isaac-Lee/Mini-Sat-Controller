package msc.services.flightdynamics;

import java.io.IOException;
import java.nio.file.Path;
import msc.orbit.*;
import msc.platform.PlatformConfiguration;
import msc.ports.OrbitComputationPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class FlightDynamicsService {
  public static void main(String[] args) {
    SpringApplication.run(FlightDynamicsService.class, args);
  }

  @Bean
  OrbitComputationPort propagation() {
    return new KeplerianOrbitAdapter();
  }

  @Bean
  OrekitReferenceFrames references(Environment env) throws IOException {
    return new OrekitReferenceFrames(
        Path.of(env.getRequiredProperty("msc.orekit.archive")),
        env.getRequiredProperty("msc.orekit.sha256"));
  }
}
