package msc.platform;

import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "msc.events.consume", havingValue = "true")
public final class InboxConsumer {
  private final StateStore store;
  private final Json json;
  private final EventHandler handler;

  public InboxConsumer(StateStore store, Json json, EventHandler handler) {
    this.store = store;
    this.json = json;
    this.handler = handler;
  }

  @RabbitListener(queues = "msc.${spring.application.name}.v1")
  public void consume(Message message) {
    var event =
        json.read(new String(message.getBody(), StandardCharsets.UTF_8), ServiceEvent.class);
    store.transaction(
        () -> {
          if (store.receive(event.eventId())) handler.handle(event);
          return null;
        });
  }
}
