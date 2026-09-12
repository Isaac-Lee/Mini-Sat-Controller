package msc.platform;

import msc.domain.time.*;
import msc.ports.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
@ComponentScan("msc.platform")
public class PlatformConfiguration {
  @Bean
  TransactionTemplate transactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  Clock missionClock(
      @Value("${msc.time.source}") String source,
      @Value("${msc.time.utc-tai-offset-seconds}") int offset,
      @Value("${msc.time.valid-from-utc}") String from,
      @Value("${msc.time.valid-until-utc}") String until) {
    return new VersionedUtcTaiClock(
        java.time.Clock.systemUTC(),
        source,
        offset,
        java.time.Instant.parse(from),
        java.time.Instant.parse(until));
  }
}
