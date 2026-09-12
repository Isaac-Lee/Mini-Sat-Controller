package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.contracts.TaskingContracts.Area;

public final class ReferenceContracts {
  private ReferenceContracts() {}

  public record Place(
      String id, long version, List<String> aliases, Area area, String approvalReference) {
    public Place {
      text(id);
      text(approvalReference);
      Objects.requireNonNull(area);
      aliases = List.copyOf(aliases);
      if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
          || version < 1
          || aliases.isEmpty()
          || aliases.size() > 100)
        throw new IllegalArgumentException("Invalid place identity/version/aliases");
      if (!area.id().equals(id + ":" + version))
        throw new IllegalArgumentException("Area identity must pin the place version");
      for (var alias : aliases) {
        text(alias);
        if (alias.length() > 500) throw new IllegalArgumentException("Alias too long");
      }
    }
  }
}
