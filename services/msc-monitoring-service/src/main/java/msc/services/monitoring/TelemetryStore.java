package msc.services.monitoring;

import java.util.*;
import msc.domain.monitoring.OperationalTelemetry.*;
import msc.platform.*;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryStore {
  private final StateStore store;
  private final msc.ports.Clock clock;

  public TelemetryStore(StateStore store, msc.ports.Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public StateStore.State<Estimate> bind(Binding binding) {
    store.lock("telemetry:" + binding.spacecraftId());
    var old = store.find("telemetry-estimate", binding.spacecraftId(), Estimate.class);
    if (old.isPresent() && old.get().body().binding().equals(binding)) return old.get();
    if (binding.version() != (old.isEmpty() ? 1 : old.get().body().binding().version() + 1))
      throw ApiException.conflict("Binding version changed");
    // A new admission policy cannot silently reuse evidence from the previous source/version.
    store.create("telemetry-binding", binding.spacecraftId() + ":" + binding.version(), binding);
    var next = Estimate.empty(binding);
    var saved =
        old.isEmpty()
            ? store.create("telemetry-estimate", binding.spacecraftId(), next)
            : store.update("telemetry-estimate", binding.spacecraftId(), old.get().version(), next);
    store.event(
        "TelemetrySourceBound",
        binding.spacecraftId(),
        saved.version(),
        UUID.randomUUID(),
        null,
        binding);
    return saved;
  }

  public StateStore.State<Receipt> ingest(Frame frame, UUID correlation, UUID causation) {
    store.lock("telemetry:" + frame.spacecraftId());
    String id = frame.spacecraftId() + ":" + frame.id();
    var old = store.find("telemetry-receipt", id, Receipt.class);
    if (old.isPresent()) {
      if (!old.get().body().frame().equals(frame))
        throw ApiException.conflict("Telemetry ID reused with different content");
      return old.get();
    }
    var state = store.require("telemetry-estimate", frame.spacecraftId(), Estimate.class);
    String sequence = frame.spacecraftId() + ":" + frame.bindingVersion() + ":" + frame.sequence();
    var earlier = store.find("telemetry-sequence", sequence, String.class);
    if (earlier.isPresent())
      throw ApiException.conflict("Telemetry sequence reused with a different frame ID");
    msc.domain.monitoring.OperationalTelemetry.Transition transition;
    if (frame.bindingVersion() < state.body().binding().version()) {
      var oldBinding =
          store
              .require(
                  "telemetry-binding",
                  frame.spacecraftId() + ":" + frame.bindingVersion(),
                  Binding.class)
              .body();
      if (!oldBinding.source().equals(frame.source()))
        throw ApiException.invalid("Telemetry source mismatch");
      transition =
          new Transition(
              state.body(), new Receipt(frame, clock.now(), Disposition.SUPERSEDED_BINDING));
    } else transition = state.body().observe(frame, clock.now());
    store.create("telemetry-sequence", sequence, frame.id().toString());
    var receipt = store.create("telemetry-receipt", id, transition.receipt());
    if (!transition.estimate().equals(state.body())) {
      var saved =
          store.update(
              "telemetry-estimate", frame.spacecraftId(), state.version(), transition.estimate());
      store.event(
          "SpacecraftStateProjected",
          frame.spacecraftId(),
          saved.version(),
          correlation,
          causation,
          saved);
    }
    store.event(
        "TelemetryFrameAccounted", id, receipt.version(), correlation, causation, receipt.body());
    return receipt;
  }
}
