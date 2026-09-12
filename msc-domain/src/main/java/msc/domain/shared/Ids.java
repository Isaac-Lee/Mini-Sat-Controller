package msc.domain.shared;

import static msc.domain.shared.Checks.text;

/** Published identifiers only; no aggregate behavior belongs here. */
public final class Ids {
  private Ids() {}

  public record RequestId(String value) {
    public RequestId {
      text(value);
    }
  }

  public record SpacecraftId(String value) {
    public SpacecraftId {
      text(value);
    }
  }

  public record PlanningRunId(String value) {
    public PlanningRunId {
      text(value);
    }
  }

  public record CandidateId(String value) {
    public CandidateId {
      text(value);
    }
  }

  public record ActivityId(String value) {
    public ActivityId {
      text(value);
    }
  }

  public record AssignmentId(String value) {
    public AssignmentId {
      text(value);
    }
  }

  public record ActivityDefinitionId(String value) {
    public ActivityDefinitionId {
      text(value);
    }
  }

  public record CommandLoadId(String value) {
    public CommandLoadId {
      text(value);
    }
  }

  public record CommandId(String value) {
    public CommandId {
      text(value);
    }
  }

  public record AcquisitionId(String value) {
    public AcquisitionId {
      text(value);
    }
  }

  public record ReceptionId(String value) {
    public ReceptionId {
      text(value);
    }
  }

  public record ProductId(String value) {
    public ProductId {
      text(value);
    }
  }

  public record OrbitSolutionId(String value) {
    public OrbitSolutionId {
      text(value);
    }
  }

  public record PropellantEstimateId(String value) {
    public PropellantEstimateId {
      text(value);
    }
  }

  public record TimeCorrelationId(String value) {
    public TimeCorrelationId {
      text(value);
    }
  }

  public record AnomalyId(String value) {
    public AnomalyId {
      text(value);
    }
  }

  public record AoiId(String value) {
    public AoiId {
      text(value);
    }
  }

  public record SnapshotId(String value) {
    public SnapshotId {
      text(value);
    }
  }

  public record ResourceId(String value) {
    public ResourceId {
      text(value);
    }
  }
}
