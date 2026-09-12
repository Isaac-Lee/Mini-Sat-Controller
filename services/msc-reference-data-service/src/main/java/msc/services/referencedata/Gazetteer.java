package msc.services.referencedata;

import java.text.Normalizer;
import java.util.*;
import msc.contracts.ReferenceContracts.Place;
import msc.contracts.TaskingContracts.*;
import msc.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class Gazetteer implements EventHandler {
  public record Head(long version, String key) {}

  private final StateStore store;
  private final JdbcTemplate db;
  private final Json json;

  public Gazetteer(StateStore store, JdbcTemplate db, Json json) {
    this.store = store;
    this.db = db;
    this.json = json;
  }

  private String normalize(String text) {
    msc.domain.shared.Checks.text(text);
    if (text.length() > 500) throw ApiException.invalid("Target too long");
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .strip()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public StateStore.State<Place> register(Place place) {
    store.lock("gazetteer:" + place.id());
    var current = store.find("gazetteer-head", place.id(), Head.class);
    long expected = current.map(v -> v.body().version() + 1).orElse(1L);
    if (place.version() != expected)
      throw ApiException.conflict("Place must advance the approved version by one");
    String key = place.id() + ":" + place.version();
    var saved = store.create("place", key, place);
    var head = new Head(place.version(), key);
    if (current.isEmpty()) store.create("gazetteer-head", place.id(), head);
    else store.update("gazetteer-head", place.id(), current.get().version(), head);
    db.update("DELETE FROM gazetteer_alias WHERE place_id=?", place.id());
    for (var alias : place.aliases().stream().map(this::normalize).distinct().toList())
      db.update(
          "INSERT INTO gazetteer_alias(alias,place_id,version) VALUES(?,?,?)",
          alias,
          place.id(),
          place.version());
    store.event(
        "ReferenceSnapshotPublished",
        key,
        place.version(),
        UUID.randomUUID(),
        null,
        Map.of(
            "kind",
            "GAZETTEER",
            "reference",
            key,
            "sourceReference",
            place.area().sourceReference()));
    return saved;
  }

  public List<Place> find(String target) {
    return db.query(
        "SELECT p.body::text FROM gazetteer_alias a JOIN state_head p ON p.kind='place' AND"
            + " p.id=a.place_id || ':' || a.version::text WHERE a.alias=? ORDER BY a.place_id LIMIT"
            + " 101",
        (rs, n) -> json.read(rs.getString(1), Place.class),
        normalize(target));
  }

  @Override
  public void handle(ServiceEvent event) {
    if (!event.type().equals("ObservationIntentReceived"))
      throw ApiException.invalid("Unsupported reference event");
    var intent = json.convert(event.payload(), IntentReceived.class);
    var places = find(intent.target());
    Optional<Area> resolved =
        places.size() == 1 ? Optional.of(places.getFirst().area()) : Optional.empty();
    String reference =
        resolved.isPresent()
            ? places.getFirst().id() + ":" + places.getFirst().version()
            : "gazetteer-query:" + event.eventId();
    var result =
        new TargetResolution(
            intent.requestId(),
            intent.revision(),
            resolved,
            reference,
            resolved.isPresent()
                ? "Resolved from approved gazetteer " + reference
                : places.isEmpty()
                    ? "Target is not in the approved gazetteer; clarify the place or supply an"
                          + " explicit area"
                    : "Multiple approved places match; choose a specific area",
            places.stream().limit(100).map(Place::area).toList());
    store.create("target-resolution", event.eventId().toString(), result);
    store.event(
        "TargetResolved",
        intent.requestId(),
        intent.revision(),
        event.correlationId(),
        event.eventId(),
        result);
  }
}
