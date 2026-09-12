package msc.services.simulator;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class SimulatorService {
  public static void main(String[] args) {
    SpringApplication.run(SimulatorService.class, args);
  }
}
