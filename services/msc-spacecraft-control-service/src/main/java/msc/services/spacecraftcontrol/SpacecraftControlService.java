package msc.services.spacecraftcontrol;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class SpacecraftControlService {
  public static void main(String[] args) {
    SpringApplication.run(SpacecraftControlService.class, args);
  }
}
