package msc.services.tasking;

import msc.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class TaskingService {
  public static void main(String[] args) {
    SpringApplication.run(TaskingService.class, args);
  }
}
