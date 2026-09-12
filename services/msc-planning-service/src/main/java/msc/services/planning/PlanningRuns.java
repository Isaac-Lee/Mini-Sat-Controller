package msc.services.planning;

import java.nio.charset.StandardCharsets;
import java.util.*;
import msc.contracts.CatalogContracts.CatalogEntry;
import msc.contracts.SimulationPlanningContracts.Model;
import msc.domain.planning.*;
import msc.domain.planning.PlanningDataSnapshot.Input;
import msc.domain.referencedata.SnapshotRef;
import msc.domain.shared.Ids.*;
import msc.platform.*;
import msc.services.planning.PlanningInputs.*;

/** Records owner-pinned runs inside the input-attempt publication fence. */
final class PlanningRuns {
  record Published(
      String id,
      String requestId,
      long requestRevision,
      String inputAttemptId,
      String spacecraftId,
      Map<Input, String> sourceHashes,
      Evidence catalog,
      Evidence simulationModel,
      Evidence geometry,
      PlanningRun.Snapshot run,
      Optional<PlanningOperations.Captured> operations) {
    Published {
      sourceHashes = Map.copyOf(sourceHashes);
      operations = operations == null ? Optional.empty() : operations;
    }
  }

  record Index(String inputAttemptId, List<String> runIds) {
    Index {
      runIds = List.copyOf(runIds);
    }
  }

  private final StateStore store;
  private final Json json;

  PlanningRuns(StateStore store, Json json) {
    this.store = store;
    this.json = json;
  }

  private String id(Object binding) {
    return UUID.nameUUIDFromBytes(json.fingerprint(binding).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  /** No network calls or latest-data substitution; caller owns the live claim transaction. */
  List<Published> publish(Attempt attempt) {
    var ids = new ArrayList<String>();
    var records = new ArrayList<Published>();
    if ("WAITING_INPUTS".equals(attempt.status()) && attempt.request().isPresent()) {
      for (var asset : attempt.assets()) {
        if (!asset.inputs().keySet().equals(EnumSet.allOf(Input.class))
            || asset.catalog().isEmpty()
            || asset.simulationModel().isEmpty()
            || asset.pointGeometry().isEmpty()
            || asset.activityOptions().isEmpty()) continue;
        var published = derive(attempt, asset);
        store.create("planning-run", published.id(), published);
        records.add(published);
        ids.add(published.id());
        store.event(
            "PlanningRunRecorded",
            attempt.requestId(),
            attempt.revision(),
            UUID.randomUUID(),
            null,
            Map.of(
                "requestId", attempt.requestId(),
                "requestRevision", attempt.revision(),
                "inputAttemptId", attempt.id(),
                "runId", published.id(),
                "spacecraftId", asset.spacecraftId()));
      }
    }
    store.create("planning-run-index", attempt.id(), new Index(attempt.id(), ids));
    return List.copyOf(records);
  }

  Published derive(Attempt attempt, Asset asset) {
    var catalogEvidence = asset.catalog().orElseThrow();
    var modelEvidence = asset.simulationModel().orElseThrow();
    var geometryEvidence = asset.pointGeometry().orElseThrow();
    for (var evidence : List.of(catalogEvidence, modelEvidence, geometryEvidence)) {
      if (!json.fingerprint(evidence.value()).equals(evidence.sha256()))
        throw ApiException.invalid("Run supporting evidence hash mismatch");
    }
    var catalog = json.convert(catalogEvidence.value(), CatalogEntry.class);
    var model = json.convert(modelEvidence.value().get("body"), Model.class);
    asset
        .operations()
        .ifPresent(
            context ->
                new PlanningOperations(null, json)
                    .validate(
                        json.convert(
                            asset.inputs().get(Input.MISSION_DEFINITION).value().path("body"),
                            msc.contracts.CatalogContracts.MissionProfile.class),
                        context));
    if (!asset.spacecraftId().equals(model.spacecraftId()))
      throw ApiException.invalid("Run model spacecraft mismatch");
    var hashes = new EnumMap<Input, String>(Input.class);
    var references = new EnumMap<Input, SnapshotRef>(Input.class);
    asset
        .inputs()
        .forEach(
            (input, evidence) -> {
              if (!json.fingerprint(evidence.value()).equals(evidence.sha256()))
                throw ApiException.invalid("Run input evidence hash mismatch");
              hashes.put(input, evidence.sha256());
              // Version one is the immutable captured evidence, not an invented owner version.
              references.put(
                  input,
                  new SnapshotRef(
                      new SnapshotId(evidence.sha256()),
                      1,
                      evidence.service() + ":" + evidence.path() + "#sha256=" + evidence.sha256(),
                      attempt.capturedAt()));
            });
    String runId =
        id(List.of(attempt.id(), attempt.requestId(), attempt.revision(), asset.spacecraftId()));
    var domainRunId = new PlanningRunId(runId);
    var requestId = new RequestId(attempt.requestId());
    var craft = new SpacecraftId(asset.spacecraftId());
    var candidates = new ArrayList<PlanCandidate>();
    var decisions = new ArrayList<DecisionRecord>();
    var opportunities = new ArrayList<Opportunity>();
    var areaId = new AoiId(geometryEvidence.value().path("area").path("id").asText());
    for (var option : asset.activityOptions()) {
      if (!option.spacecraftId().equals(asset.spacecraftId())
          || !option.definitionId().equals(catalog.activity().id().value())
          || option.definitionVersion() != catalog.activity().version()
          || option.phase() != model.phase()
          || !option.mode().equals(model.mode()))
        throw ApiException.invalid("Run option does not match pinned definition/model");
      String candidateId = id(List.of(runId, option));
      var candidate =
          PlanCandidate.propose(
              new CandidateId(candidateId),
              domainRunId,
              requestId,
              craft,
              new ActivityId(id(List.of(candidateId, "activity"))),
              option.window(),
              catalog.activity(),
              option.phase(),
              option.mode(),
              new FeasibilityEvaluation(
                  FeasibilityEvaluation.Status.NOT_EVALUATED,
                  "planning-run:" + runId + "/candidate/" + candidateId));
      candidates.add(candidate);
      opportunities.add(new Opportunity(craft, areaId, option.window()));
      var remaining = new LinkedHashSet<>(option.remainingGates());
      remaining.addAll(asset.missing());
      remaining.addAll(attempt.issues());
      decisions.add(
          new DecisionRecord(
              candidate.id(), "Pending evaluation: " + String.join(", ", remaining)));
    }
    var run =
        new PlanningRun(
            domainRunId,
            requestId,
            attempt.capturedAt(),
            new PlanningDataSnapshot(references),
            opportunities,
            candidates,
            decisions);
    return new Published(
        runId,
        attempt.requestId(),
        attempt.revision(),
        attempt.id(),
        asset.spacecraftId(),
        hashes,
        catalogEvidence,
        modelEvidence,
        geometryEvidence,
        run.snapshot(),
        asset.operations());
  }
}
