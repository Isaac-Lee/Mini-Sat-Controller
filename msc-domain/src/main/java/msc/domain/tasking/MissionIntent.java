package msc.domain.tasking;

import static msc.domain.shared.Checks.text;

public record MissionIntent(String target) {
  public MissionIntent {
    text(target);
  }
}
