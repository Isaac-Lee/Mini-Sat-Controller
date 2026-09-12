package msc.domain.spacecraftcontrol;

import static msc.domain.shared.Checks.text;

import java.util.Map;
import java.util.Objects;
import msc.domain.shared.Ids.CommandId;
import msc.domain.time.OnboardTime;

/** Semantic command template and parameters, not encoded transport bytes. */
public record CommandInstance(
    CommandId id, String templateReference, Map<String, String> parameters, OnboardTime timeTag) {
  public CommandInstance {
    Objects.requireNonNull(id);
    text(templateReference);
    parameters = Map.copyOf(parameters);
    Objects.requireNonNull(timeTag);
  }
}
