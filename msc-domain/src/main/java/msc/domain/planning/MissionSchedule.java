package msc.domain.planning;

import java.util.*;
import msc.domain.shared.Ids.*;
import msc.domain.time.MissionInstant;
import msc.domain.time.TimeWindow;

/** Immutable committed snapshot. Durable publication belongs to the atomic repository port. */
public final class MissionSchedule {
  private final ScheduleKey key;
  private final long version;
  private final MissionInstant frozenUntil;
  private final List<ScheduledActivity> activities;
  private final List<Assignment> assignments;
  private final ResourceValidation resourceValidation;

  private MissionSchedule(
      ScheduleKey key,
      long version,
      MissionInstant frozenUntil,
      List<ScheduledActivity> activities,
      List<Assignment> assignments,
      ResourceValidation validation) {
    this.key = Objects.requireNonNull(key);
    this.version = version;
    Objects.requireNonNull(frozenUntil).requireTai();
    if (frozenUntil.compareTo(key.horizon().start()) < 0
        || frozenUntil.compareTo(key.horizon().end()) > 0)
      throw new IllegalArgumentException("Frozen boundary outside horizon");
    this.frozenUntil = frozenUntil;
    this.activities = List.copyOf(activities);
    this.assignments = List.copyOf(assignments);
    this.resourceValidation = Objects.requireNonNull(validation);
  }

  /** Published persistence representation; reconstruction rechecks structural invariants. */
  public record Snapshot(
      ScheduleKey key,
      long version,
      MissionInstant frozenUntil,
      List<ScheduledActivity> activities,
      List<Assignment> assignments,
      ResourceValidation resourceValidation) {
    public Snapshot {
      Objects.requireNonNull(key);
      Objects.requireNonNull(frozenUntil);
      activities = List.copyOf(activities);
      assignments = List.copyOf(assignments);
      Objects.requireNonNull(resourceValidation);
    }
  }

  public Snapshot snapshot() {
    return new Snapshot(key, version, frozenUntil, activities, assignments, resourceValidation);
  }

  public static MissionSchedule restore(Snapshot snapshot) {
    if (snapshot.version() < 0
        || (snapshot.version() == 0
            && (!snapshot.activities().isEmpty()
                || !snapshot.assignments().isEmpty()
                || !snapshot.frozenUntil().equals(snapshot.key().horizon().start()))))
      throw new IllegalArgumentException("Invalid persisted schedule version");
    var activityIds = new HashSet<ActivityId>();
    var resources = new HashMap<ResourceId, List<ScheduledActivity>>();
    for (var activity : snapshot.activities()) {
      if (!activityIds.add(activity.id()) || !snapshot.key().horizon().contains(activity.window()))
        throw new IllegalArgumentException("Invalid persisted activity identity/window");
      for (var resource : activity.exclusiveResources())
        resources.computeIfAbsent(resource, key -> new ArrayList<>()).add(activity);
    }
    for (var occupied : resources.values()) {
      occupied.sort(Comparator.comparing(activity -> activity.window().start()));
      for (int i = 1; i < occupied.size(); i++)
        if (occupied.get(i - 1).window().overlaps(occupied.get(i).window()))
          throw new IllegalArgumentException("Persisted exclusive resource conflict");
    }
    var assignmentIds = new HashSet<AssignmentId>();
    var candidateIds = new HashSet<CandidateId>();
    var assignedActivities = new HashSet<ActivityId>();
    for (var assignment : snapshot.assignments()) {
      if (!assignmentIds.add(assignment.id())
          || !candidateIds.add(assignment.candidateId())
          || !assignedActivities.add(assignment.activityId())
          || !activityIds.contains(assignment.activityId()))
        throw new IllegalArgumentException("Invalid persisted assignment graph");
    }
    if (!assignedActivities.equals(activityIds))
      throw new IllegalArgumentException("Unassigned persisted activity");
    return new MissionSchedule(
        snapshot.key(),
        snapshot.version(),
        snapshot.frozenUntil(),
        snapshot.activities(),
        snapshot.assignments(),
        snapshot.resourceValidation());
  }

  /** Withdraw only not-yet-frozen work; keep past/in-flight history in the new snapshot. */
  public MissionSchedule withdrawFuture(RequestId requestId, MissionInstant cutoff) {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(cutoff).requireTai();
    if (cutoff.compareTo(frozenUntil) < 0 || cutoff.compareTo(key.horizon().end()) > 0)
      throw new IllegalArgumentException("Invalid withdrawal/frozen boundary");
    var byId = new HashMap<ActivityId, ScheduledActivity>();
    activities.forEach(activity -> byId.put(activity.id(), activity));
    var removed = new HashSet<ActivityId>();
    for (var assignment : assignments)
      if (assignment.requestId().equals(requestId)
          && byId.get(assignment.activityId()).window().start().compareTo(cutoff) >= 0)
        removed.add(assignment.activityId());
    if (removed.isEmpty() && cutoff.equals(frozenUntil)) return this;
    return new MissionSchedule(
        key,
        Math.addExact(version, 1),
        cutoff,
        activities.stream().filter(activity -> !removed.contains(activity.id())).toList(),
        assignments.stream()
            .filter(assignment -> !removed.contains(assignment.activityId()))
            .toList(),
        ResourceValidation.notEvaluated());
  }

  /** Repository publication guard for append, future withdrawal, and monotonic freezing. */
  public void requireSuccessorOf(MissionSchedule previous) {
    Objects.requireNonNull(previous);
    if (!key.equals(previous.key)
        || version != Math.addExact(previous.version, 1)
        || frozenUntil.compareTo(previous.frozenUntil) < 0)
      throw new IllegalArgumentException("Invalid schedule successor");
    var currentByActivity = new HashMap<ActivityId, Assignment>();
    assignments.forEach(a -> currentByActivity.put(a.activityId(), a));
    var currentActivities = new HashMap<ActivityId, ScheduledActivity>();
    activities.forEach(a -> currentActivities.put(a.id(), a));
    var previousAssignments = new HashMap<ActivityId, Assignment>();
    previous.assignments.forEach(a -> previousAssignments.put(a.activityId(), a));
    int removed = 0, added = 0;
    for (var old : previous.activities) {
      var retained = currentActivities.get(old.id());
      if (retained == null) {
        removed++;
        if (old.window().start().compareTo(frozenUntil) < 0)
          throw new IllegalArgumentException("Cannot remove frozen activity");
      } else if (!old.equals(retained)
          || !previousAssignments.get(old.id()).equals(currentByActivity.get(old.id())))
        throw new IllegalArgumentException("Cannot rewrite retained activity or assignment");
    }
    for (var activity : activities)
      if (!previousAssignments.containsKey(activity.id())) {
        added++;
        if (activity.window().start().compareTo(previous.frozenUntil) < 0)
          throw new IllegalArgumentException("Cannot add activity inside frozen history");
        var assignment = currentByActivity.get(activity.id());
        if (previous.assignments.stream()
            .anyMatch(
                old ->
                    old.id().equals(assignment.id())
                        || old.candidateId().equals(assignment.candidateId())))
          throw new IllegalArgumentException("Cannot reuse prior assignment/candidate identity");
      }
    if (added > 1 || (added > 0 && removed > 0))
      throw new IllegalArgumentException("One append or future withdrawal per schedule version");
  }

  public static MissionSchedule empty(ScheduleKey key) {
    return new MissionSchedule(
        key, 0, key.horizon().start(), List.of(), List.of(), ResourceValidation.notEvaluated());
  }

  /** Add an approved proposal; cancellation uses a new snapshot through withdrawFuture. */
  public MissionSchedule commit(
      PlanCandidate candidate, AssignmentId assignmentId, MissionInstant newFrozenUntil) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(assignmentId);
    if (!candidate.spacecraftId().equals(key.spacecraftId()))
      throw new IllegalArgumentException("Wrong spacecraft");
    if (!candidate.feasibility().feasible())
      throw new IllegalArgumentException("Proposal must have a completed feasible evaluation");
    ScheduledActivity next = candidate.activity();
    if (!key.horizon().contains(next.window()))
      throw new IllegalArgumentException("Activity outside horizon");
    if (next.window().start().compareTo(frozenUntil) < 0)
      throw new IllegalArgumentException("Frozen horizon");
    if (newFrozenUntil.compareTo(frozenUntil) < 0)
      throw new IllegalArgumentException("Cannot unfreeze history");
    if (assignments.stream()
        .anyMatch(a -> a.id().equals(assignmentId) || a.candidateId().equals(candidate.id())))
      throw new IllegalArgumentException("Duplicate assignment/candidate");
    for (ScheduledActivity existing : activities) {
      if (existing.id().equals(next.id()))
        throw new IllegalArgumentException("Duplicate activity ID");
      if (existing.window().overlaps(next.window())
          && !Collections.disjoint(existing.exclusiveResources(), next.exclusiveResources()))
        throw new IllegalArgumentException("Exclusive resource conflict");
    }
    var newActivities = new ArrayList<>(activities);
    newActivities.add(next);
    var newAssignments = new ArrayList<>(assignments);
    newAssignments.add(
        new Assignment(
            assignmentId, candidate.id(), candidate.requestId(), candidate.runId(), next.id()));
    // A changed timeline invalidates previous reservoir/external validation evidence.
    return new MissionSchedule(
        key,
        Math.addExact(version, 1),
        newFrozenUntil,
        newActivities,
        newAssignments,
        ResourceValidation.notEvaluated());
  }

  public ScheduleKey key() {
    return key;
  }

  /** Exclusive resources belong to a spacecraft, not to a chosen schedule horizon key. */
  public void requireCompatibleWith(MissionSchedule other) {
    Objects.requireNonNull(other);
    if (!key.spacecraftId().equals(other.key.spacecraftId())) return;
    var ids = new HashSet<ActivityId>();
    var resources = new HashMap<ResourceId, List<ScheduledActivity>>();
    for (var schedule : List.of(this, other)) {
      for (var activity : schedule.activities) {
        if (!ids.add(activity.id()))
          throw new IllegalArgumentException("Activity is owned by multiple schedule horizons");
        for (var resource : activity.exclusiveResources())
          resources.computeIfAbsent(resource, ignored -> new ArrayList<>()).add(activity);
      }
    }
    for (var occupied : resources.values()) {
      occupied.sort(Comparator.comparing(activity -> activity.window().start()));
      for (int i = 1; i < occupied.size(); i++) {
        if (occupied.get(i - 1).window().overlaps(occupied.get(i).window()))
          throw new IllegalArgumentException(
              "Exclusive resource conflict across schedule horizons");
      }
    }
  }

  /** A different horizon key cannot reopen another head's frozen physical interval. */
  public void requireCrossHorizonSuccessorOf(MissionSchedule previous, MissionSchedule other) {
    if (!key.equals(previous.key)) throw new IllegalArgumentException("Wrong predecessor key");
    requireCompatibleWith(other);
    if (!key.spacecraftId().equals(other.key.spacecraftId())
        || other.frozenUntil.equals(other.key.horizon().start())) return;
    var frozen = new TimeWindow(other.key.horizon().start(), other.frozenUntil);
    var retained = new HashSet<ActivityId>();
    previous.activities.forEach(activity -> retained.add(activity.id()));
    for (var activity : activities) {
      if (!retained.contains(activity.id()) && activity.window().overlaps(frozen))
        throw new IllegalArgumentException("Frozen interval in another schedule horizon");
    }
  }

  public long version() {
    return version;
  }

  public MissionInstant frozenUntil() {
    return frozenUntil;
  }

  public List<ScheduledActivity> activities() {
    return activities;
  }

  public List<Assignment> assignments() {
    return assignments;
  }

  public ResourceValidation resourceValidation() {
    return resourceValidation;
  }
}
