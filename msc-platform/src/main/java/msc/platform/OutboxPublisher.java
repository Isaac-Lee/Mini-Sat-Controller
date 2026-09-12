package msc.platform;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final RabbitTemplate rabbit;

  public OutboxPublisher(JdbcTemplate db, TransactionTemplate tx, RabbitTemplate rabbit) {
    this.db = db;
    this.tx = tx;
    this.rabbit = rabbit;
  }

  @Scheduled(fixedDelayString = "${msc.outbox.delay-ms:250}")
  public void publish() {
    tx.executeWithoutResult(
        status -> {
          var events =
              db.query(
                  "SELECT event_id::text,event_type,envelope::text FROM outbox WHERE published_at"
                      + " IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 16",
                  (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
          for (var event : events) {
            try {
              var data = new CorrelationData(event[0]);
              var properties = new MessageProperties();
              properties.setContentType("application/json");
              properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
              properties.setMessageId(event[0]);
              rabbit.send(
                  EventTopology.EXCHANGE,
                  event[1],
                  new Message(
                      event[2].getBytes(java.nio.charset.StandardCharsets.UTF_8), properties),
                  data);
              var confirm = data.getFuture().get(5, TimeUnit.SECONDS);
              if (!confirm.isAck() || data.getReturned() != null)
                throw new IllegalStateException("Broker did not route and confirm event");
              db.update(
                  "UPDATE outbox SET"
                      + " published_at=clock_timestamp(),attempts=attempts+1,last_error=NULL WHERE"
                      + " event_id=?::uuid",
                  event[0]);
            } catch (Exception e) {
              if (e instanceof InterruptedException) Thread.currentThread().interrupt();
              db.update(
                  "UPDATE outbox SET attempts=attempts+1,last_error=? WHERE event_id=?::uuid",
                  e.getClass().getSimpleName(),
                  event[0]);
              log.warn("Outbox {} awaiting retry: {}", event[0], e.getClass().getSimpleName());
              break;
            }
          }
        });
  }
}
