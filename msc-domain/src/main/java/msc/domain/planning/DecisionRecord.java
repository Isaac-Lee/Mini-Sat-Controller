package msc.domain.planning;

import static msc.domain.shared.Checks.text;

import java.util.Objects;
import msc.domain.shared.Ids.CandidateId;

public record DecisionRecord(CandidateId candidateId, String rationale) {
  public DecisionRecord {
    Objects.requireNonNull(candidateId);
    text(rationale);
  }
}
