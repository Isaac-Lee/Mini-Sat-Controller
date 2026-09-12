package msc.services.groundoperations;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class GroundOperationsService {
  public static void main(String[] args) {
    SpringApplication.run(GroundOperationsService.class, args);
  }
}
