package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.time.MissionInstant;

/** Uncertainty representation/units are explicit mission-model metadata, not assumed zero. */
public record EstimateContext(
    MissionInstant epoch,
    String referenceFrame,
    String uncertaintyReference,
    String modelVersion,
    String configurationVersion,
    SnapshotRef provenance) {
  public EstimateContext {
    Objects.requireNonNull(epoch);
    epoch.requireTai();
    text(referenceFrame);
    text(uncertaintyReference);
    text(modelVersion);
    text(configurationVersion);
    Objects.requireNonNull(provenance);
  }
}
