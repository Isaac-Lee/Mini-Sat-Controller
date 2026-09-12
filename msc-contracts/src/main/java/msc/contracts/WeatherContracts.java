package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import java.util.UUID;
import msc.contracts.TaskingContracts.Area;
import msc.domain.time.*;

public final class WeatherContracts {
  private WeatherContracts() {}

  /** Explicit uniform synthetic cloud field, not an observed regional cloud average. */
  public record Forecast(
      String id,
      String environment,
      MissionInstant issuedAt,
      TimeWindow validFor,
      Area area,
      double cloudFraction,
      String provenance) {
    public Forecast {
      UUID.fromString(id);
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException(
            "Only explicitly simulated uniform cloud fields are supported");
      Objects.requireNonNull(issuedAt).requireTai();
      Objects.requireNonNull(validFor);
      Objects.requireNonNull(area);
      text(provenance);
      if (issuedAt.compareTo(validFor.end()) >= 0
          || !Double.isFinite(cloudFraction)
          || cloudFraction < 0
          || cloudFraction > 1)
        throw new IllegalArgumentException("Invalid forecast issue time or cloud fraction");
    }

    public boolean covers(Query query) {
      return validFor.contains(query.horizon())
          && area.west() <= query.area().west()
          && area.east() >= query.area().east()
          && area.south() <= query.area().south()
          && area.north() >= query.area().north();
    }
  }

  public record Query(Area area, TimeWindow horizon) {
    public Query {
      Objects.requireNonNull(area);
      Objects.requireNonNull(horizon);
    }
  }
}
