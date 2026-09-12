package msc.services.acquisition;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class AcquisitionService {
  public static void main(String[] args) {
    SpringApplication.run(AcquisitionService.class, args);
  }
}
