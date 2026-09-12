package msc.platform;

public interface EventHandler {
  /**
   * Called within the inbox/local-state/outbox transaction. Throwing rolls back all local effects.
   */
  void handle(ServiceEvent event);
}
