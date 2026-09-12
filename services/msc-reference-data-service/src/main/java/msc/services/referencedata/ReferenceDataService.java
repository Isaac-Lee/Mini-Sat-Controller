package msc.services.referencedata;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class ReferenceDataService {
  public static void main(String[] args) {
    SpringApplication.run(ReferenceDataService.class, args);
  }
}
