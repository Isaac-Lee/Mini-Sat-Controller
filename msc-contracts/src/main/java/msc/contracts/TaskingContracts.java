package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.*;
import msc.domain.tasking.ObservationRequest;
import msc.domain.tasking.ObservationRequest.InteractionPreference;
import msc.domain.time.MissionInstant;

public final class TaskingContracts {
  private TaskingContracts() {}

  /**
   * Non-antimeridian WGS84 rectangle. Larger/split AOIs require explicit future geometry support.
   */
  public record Area(
      String id, double west, double south, double east, double north, String sourceReference) {
    public Area {
      text(id);
      text(sourceReference);
      if (!Double.isFinite(west)
          || !Double.isFinite(east)
          || !Double.isFinite(south)
          || !Double.isFinite(north)
          || west < -180
          || east > 180
          || south < -90
          || north > 90
          || west >= east
          || south >= north) throw new IllegalArgumentException("Invalid WGS84 area bounds");
    }
  }

  public record Criteria(double minimumCoverageFraction, double maximumCloudFraction) {
    public Criteria {
      if (!Double.isFinite(minimumCoverageFraction)
          || minimumCoverageFraction <= 0
          || minimumCoverageFraction > 1
          || !Double.isFinite(maximumCloudFraction)
          || maximumCloudFraction < 0
          || maximumCloudFraction > 1)
        throw new IllegalArgumentException(
            "Coverage and cloud fractions must be within 0..1, coverage positive");
    }
  }

  public record Submission(
      String target,
      Optional<Area> area,
      Optional<Criteria> criteria,
      Optional<MissionInstant> deadline,
      int priority,
      InteractionPreference preference) {
    public Submission {
      text(target);
      if (target.length() > 500) throw new IllegalArgumentException("Target is too long");
      area = area == null ? Optional.empty() : area;
      criteria = criteria == null ? Optional.empty() : criteria;
      deadline = deadline == null ? Optional.empty() : deadline;
      deadline.ifPresent(MissionInstant::requireTai);
      preference = preference == null ? InteractionPreference.AUTO : preference;
      if (priority < 0 || priority > 100)
        throw new IllegalArgumentException("Priority must be 0..100");
    }
  }

  public record RequestDetails(
      ObservationRequest request,
      String owner,
      String target,
      Optional<Area> area,
      Criteria criteria,
      MissionInstant createdAt,
      MissionInstant updatedAt,
      String reason,
      List<Area> clarificationOptions) {
    public RequestDetails {
      Objects.requireNonNull(request);
      text(owner);
      text(target);
      Objects.requireNonNull(area);
      Objects.requireNonNull(criteria);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(updatedAt);
      Objects.requireNonNull(reason);
      clarificationOptions = List.copyOf(clarificationOptions);
    }
  }

  public record IntentReceived(String requestId, long revision, String target) {}

  public record TargetResolution(
      String requestId,
      long revision,
      Optional<Area> area,
      String reference,
      String reason,
      List<Area> candidates) {
    public TargetResolution {
      text(requestId);
      if (revision < 1) throw new IllegalArgumentException("Invalid request revision");
      Objects.requireNonNull(area);
      text(reference);
      text(reason);
      candidates = List.copyOf(candidates);
    }
  }

  public record AcceptedRequest(
      String requestId,
      long revision,
      Area area,
      Criteria criteria,
      Optional<MissionInstant> deadline,
      int priority) {}

  public record RequestProgress(String requestId, long revision, String evidenceReference) {
    public RequestProgress {
      text(requestId);
      text(evidenceReference);
      if (revision < 1) throw new IllegalArgumentException("Invalid request revision");
    }
  }

  public record QualityEvidence(
      String requestId,
      long revision,
      String productReference,
      String acquisitionReference,
      double unionCoverageFraction,
      double cloudFraction,
      String assessmentReference) {
    public QualityEvidence {
      text(requestId);
      text(productReference);
      text(acquisitionReference);
      text(assessmentReference);
      if (revision < 1
          || !Double.isFinite(unionCoverageFraction)
          || unionCoverageFraction < 0
          || unionCoverageFraction > 1
          || !Double.isFinite(cloudFraction)
          || cloudFraction < 0
          || cloudFraction > 1)
        throw new IllegalArgumentException("Invalid measured quality evidence");
    }
  }
}
