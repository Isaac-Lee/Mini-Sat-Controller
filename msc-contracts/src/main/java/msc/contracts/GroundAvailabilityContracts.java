package msc.contracts;

import java.util.*;
import msc.contracts.GroundContracts.Station;
import msc.domain.time.*;

/** Local allocation evidence. Free intervals still require provider-confirmed reservation. */
public final class GroundAvailabilityContracts {
  private GroundAvailabilityContracts() {}

  public record Query(TimeWindow horizon) {
    public Query {
      Objects.requireNonNull(horizon);
      if (GroundContracts.seconds(horizon) > 86400)
        throw new IllegalArgumentException("Availability horizon must not exceed 24 hours");
    }
  }

  public record Allocation(String bookingId, TimeWindow window) {}

  public record StationAvailability(
      Station station, List<Allocation> allocations, List<TimeWindow> free) {
    public StationAvailability {
      Objects.requireNonNull(station);
      allocations = List.copyOf(allocations);
      free = List.copyOf(free);
    }
  }

  public record Snapshot(
      String id,
      MissionInstant capturedAt,
      Query query,
      String scope,
      List<StationAvailability> stations) {
    public Snapshot {
      stations = List.copyOf(stations);
    }
  }

  public static List<TimeWindow> free(TimeWindow horizon, List<Allocation> allocations) {
    var result = new ArrayList<TimeWindow>();
    var cursor = horizon.start();
    for (var item :
        allocations.stream().sorted(Comparator.comparing(a -> a.window().start())).toList()) {
      var window = item.window();
      if (!window.overlaps(horizon)) continue;
      var start = window.start().compareTo(horizon.start()) < 0 ? horizon.start() : window.start();
      var end = window.end().compareTo(horizon.end()) > 0 ? horizon.end() : window.end();
      if (cursor.compareTo(start) < 0) result.add(new TimeWindow(cursor, start));
      if (cursor.compareTo(end) < 0) cursor = end;
    }
    if (cursor.compareTo(horizon.end()) < 0) result.add(new TimeWindow(cursor, horizon.end()));
    return List.copyOf(result);
  }
}
