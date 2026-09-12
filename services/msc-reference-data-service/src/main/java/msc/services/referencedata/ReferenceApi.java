package msc.services.referencedata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import msc.contracts.ReferenceContracts.Place;
import msc.platform.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReferenceApi {
  private final StateStore store;
  private final Gazetteer gazetteer;

  public ReferenceApi(StateStore store, Gazetteer gazetteer) {
    this.store = store;
    this.gazetteer = gazetteer;
  }

  @PostMapping("/api/places")
  @PreAuthorize("hasRole('ADMIN')")
  public JsonNode create(
      @RequestBody Place place,
      @RequestHeader("Idempotency-Key") String key,
      Authentication actor) {
    return store.idempotent(
        "place:" + actor.getName(), key, place, () -> gazetteer.register(place));
  }

  @GetMapping({"/api/places/{id}/versions/{version}", "/internal/places/{id}/versions/{version}"})
  public Place place(@PathVariable String id, @PathVariable long version) {
    return store.require("place", id + ":" + version, Place.class).body();
  }

  @GetMapping("/api/places")
  public List<Place> search(@RequestParam String target) {
    return gazetteer.find(target);
  }
}
