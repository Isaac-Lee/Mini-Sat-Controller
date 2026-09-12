package msc.domain.product;

import static msc.domain.shared.Checks.text;

import java.util.List;
import java.util.Objects;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;

/**
 * Lossless instrument-source manifest, including gap/quality metadata. No final format is selected.
 */
public record L0Product(
    ProductId id,
    AcquisitionId acquisitionId,
    String instrumentSourceManifest,
    String packetIndexReference,
    String gapQualityReference,
    List<SnapshotRef> ancillaryReferences) {
  public L0Product {
    Objects.requireNonNull(id);
    Objects.requireNonNull(acquisitionId);
    text(instrumentSourceManifest);
    text(packetIndexReference);
    text(gapQualityReference);
    ancillaryReferences = List.copyOf(ancillaryReferences);
  }
}
