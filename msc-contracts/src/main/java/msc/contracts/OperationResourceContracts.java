package msc.contracts;

import static msc.domain.shared.Checks.text;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Version-1 published per-operation resource evidence for a spacecraft, keyed by {@code
 * spacecraftId}; no server or persistence dependencies.
 *
 * <p><b>This is resource evidence only.</b> It selects no activity and establishes no precedence
 * over {@link MissionCatalogBindingContracts} or the legacy single-catalog {@link
 * CatalogContracts.MissionProfile} pin — both stay untouched and keep their existing meaning.
 * Publishing a profile here does not make an activity approved, scheduled, or feasible; it only
 * records, per bound catalog entry, which resource numbers an ADMIN has confirmed still match
 * that catalog entry at publish time.
 *
 * <p><b>{@code expected} is a tripwire, never a second source of truth.</b> {@code
 * OperationResourceApi.publish} rejects the document unless every field of {@link
 * ExpectedCatalogResources} equals the corresponding field of the resolved {@code
 * CatalogContracts.CatalogEntry#resources()} exactly. This exists only to catch a catalog entry
 * that has been swapped out from underneath an already-drafted profile; it must never be read as
 * an independent or authoritative resource figure. The catalog remains the only source of
 * resource numbers in this codebase.
 *
 * <p><b>A {@code DOWNLINK} profile does not imply a ground contact exists.</b> Booking and pass
 * availability remain a separate {@code GROUND_RESERVATION} gate, {@code NOT_EVALUATED} by this
 * contract. This is the main overstated-safety risk in this slice: a resource forecast showing
 * accumulated storage being drained by a {@code DOWNLINK} activity must never be read as "the
 * data will actually get down" — whether a ground station is actually in view, booked, and able
 * to receive at that rate is not evaluated here or anywhere else in this codebase yet.
 *
 * <p>No thermal or wheel-momentum content is expressed here, and nothing here is or implies a
 * physical-truth or feasibility claim. {@code msc.domain.planning.ResourceTimeline} models a
 * {@code Load}'s rates as constant across an activity's whole window; a real downlink rate varies
 * with ground-station elevation over the contact, which this contract does not attempt to model.
 *
 * <p>Nothing here is invented: every {@code (catalogId, catalogVersion)} is an explicit
 * administrator input, resolved at publish time against the already-approved activity catalog
 * (see {@code CatalogContracts.CatalogEntry}), and every {@code downlinkMegabytesPerSecond} is an
 * explicit administrator-supplied rate, never an inferred station throughput. No instrument,
 * station throughput, activity duration, or resource value is invented anywhere in this contract.
 * Nothing may be derived from NORAD identity or public orbit elements: SPACEEYE-T1 / NORAD 63229
 * is public GP orbit tracking only, never a hardware or instrument source.
 */
public final class OperationResourceContracts {
  private OperationResourceContracts() {}

  /**
   * Bounded, explicitly enumerated operation. Values are the exact {@code
   * CatalogContracts.CommandTemplate#operation()} strings a resolved catalog entry must declare;
   * {@code OperationResourceApi.publish} rejects a profile whose resolved catalog entry declares
   * a different operation string.
   *
   * <p>This is deliberately <b>not</b> {@code MissionCatalogBindingContracts.Role}: a role label
   * names a spacecraft-level slot ({@code IMAGING}, {@code DOWNLINK}, {@code MANEUVER}) that a
   * catalog reference is bound into, while {@code Operation} here names the template operation a
   * single profile's resource numbers are evidence for. Conflating the two would let a role label
   * silently redefine what operation a profile's resource numbers actually apply to, which is
   * exactly the confusion the accepted {@code MissionCatalogBindingContracts.Role} javadoc warns
   * against for role labels in general.
   */
  public enum Operation {
    IMAGE,
    DOWNLINK,
    MANEUVER
  }

  /**
   * The catalog resource numbers a profile expects the bound catalog entry to still carry, in the
   * same shape and units as {@code CatalogContracts.ResourceProfile}. See the class javadoc:
   * this is a tripwire checked for exact equality against the resolved catalog entry at publish
   * time, never an independent or authoritative resource figure.
   */
  public record ExpectedCatalogResources(
      double powerWatts, double generatedMegabytes, double propellantKilograms) {
    public ExpectedCatalogResources {
      for (double value : new double[] {powerWatts, generatedMegabytes, propellantKilograms})
        if (!Double.isFinite(value) || value < 0)
          throw new IllegalArgumentException("Invalid expected catalog resources");
    }
  }

  /**
   * One catalog entry's resource evidence for exactly one operation. {@code catalog} reuses
   * {@link MissionCatalogBindingContracts.CatalogReference} read-only rather than defining a
   * second, identical, exact-version reference record.
   *
   * <p>{@code downlinkMegabytesPerSecond} is a rate in megabytes per second, not a total, because
   * {@code msc.domain.planning.ResourceTimeline.Load} consumes rates ({@code
   * generatedMbPerSecond}/{@code downlinkedMbPerSecond}, both megabytes per second) while the
   * catalog only ever publishes a total ({@code generatedMegabytes}); it must be supplied as a
   * rate or the units used downstream will not line up. It is required, finite, strictly greater
   * than zero, and bounded above by {@link #MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND} for {@code
   * DOWNLINK}, and must be {@code null} for {@code IMAGE} and {@code MANEUVER}. A zero rate is
   * deliberately rejected, not merely a nonnegative one accepted: a zero downlink rate would
   * silently produce a storage reservoir that never drains while the profile still reports as
   * validated, which is exactly the failure mode this project must avoid. The upper bound is an
   * implementation/SIMULATION validation bound in this project's existing bounded-parameter
   * style, kept small and finite on principle — it is <b>not</b> a spacecraft or ground-station
   * throughput specification and must never be read as one. A positive rate here describes an
   * active transfer profile only; it does not prove a ground contact exists — booking and pass
   * availability remain a separate {@code GROUND_RESERVATION} gate, {@code NOT_EVALUATED} by this
   * contract (see the class javadoc).
   *
   * <p>{@code expected}'s {@code generatedMegabytes} and {@code propellantKilograms} are carried
   * through unconstrained beyond {@link ExpectedCatalogResources}'s own nonnegative-finite check
   * and the tripwire equality against the resolved catalog entry (see the class javadoc): a
   * {@code DOWNLINK} activity may legitimately also produce nonzero housekeeping/overhead data
   * alongside its explicit drain — {@code ResourceTimeline} already nets production and drain on
   * one {@code Load} ({@code storageRate = Math.max(0, production) - Math.max(0, downlink)},
   * {@code ResourceTimeline.java:222}) — and nothing in this codebase defines {@code MANEUVER} as
   * necessarily consuming propellant, so a zero-propellant {@code MANEUVER} entry is not rejected
   * here. Unmodeled manoeuvre physics and attitude feasibility remain a separate gate.
   */
  public record OperationResourceProfile(
      Operation operation,
      MissionCatalogBindingContracts.CatalogReference catalog,
      ExpectedCatalogResources expected,
      Double downlinkMegabytesPerSecond) {
    /** Upper bound on an explicit, administrator-supplied downlink rate; never an inferred one. */
    public static final double MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND = 10_000;

    public OperationResourceProfile {
      Objects.requireNonNull(operation, "Operation is required");
      Objects.requireNonNull(catalog, "Catalog reference is required");
      Objects.requireNonNull(expected, "Expected catalog resources are required");
      if (operation == Operation.DOWNLINK) {
        if (downlinkMegabytesPerSecond == null
            || !Double.isFinite(downlinkMegabytesPerSecond)
            || downlinkMegabytesPerSecond <= 0
            || downlinkMegabytesPerSecond > MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND)
          throw new IllegalArgumentException(
              "DOWNLINK requires a finite downlink rate in (0, "
                  + MAXIMUM_DOWNLINK_MEGABYTES_PER_SECOND
                  + "] Mb/s");
      } else if (downlinkMegabytesPerSecond != null) {
        throw new IllegalArgumentException(operation + " must not declare a downlink rate");
      }
    }
  }

  /**
   * All published operation resource profiles for one spacecraft, one document per {@code
   * spacecraftId} holding the full list — mirroring the accepted {@code
   * MissionCatalogBindingContracts.Bindings} precedent, not a composite {@code (spacecraftId,
   * catalogId, catalogVersion)} key. This keeps one CAS stream, one advisory lock and one version
   * history per spacecraft, and lets publish check cross-profile consistency (duplicate catalog
   * references) in one place.
   *
   * <p>{@code missionDefinitionVersion} must match the spacecraft's currently stored {@code
   * CatalogContracts.MissionProfile#missionDefinitionVersion()}, checked by {@code
   * OperationResourceApi} exactly as {@code AgilityApi} and {@code SimulationPlanningModelApi}
   * check it. {@code environment} must be exactly {@code "SIMULATION"}; any assumption expressed
   * by a profile is a simulation-only assumption, never a live one. {@code approvalReference} and
   * {@code provenance} are required free-text administrator inputs, exactly as elsewhere in this
   * codebase's mission-definition contracts.
   *
   * <p>Duplicate catalog references (the same {@code (catalogId, catalogVersion)} bound by more
   * than one profile) are rejected here, structurally, since they need no persistence lookup to
   * detect. At least one profile is required: a published document with no resource evidence at
   * all carries nothing worth publishing.
   */
  public record Profiles(
      String spacecraftId,
      String missionDefinitionVersion,
      String environment,
      List<OperationResourceProfile> profiles,
      String approvalReference,
      String provenance) {
    public Profiles {
      text(spacecraftId);
      text(missionDefinitionVersion);
      if (!"SIMULATION".equals(environment))
        throw new IllegalArgumentException(
            "Only explicitly simulated operation resource profiles are supported");
      text(approvalReference);
      text(provenance);
      Objects.requireNonNull(profiles, "Profiles are required");
      profiles = List.copyOf(profiles);
      if (profiles.isEmpty())
        throw new IllegalArgumentException("At least one operation resource profile is required");
      var seen = new HashSet<MissionCatalogBindingContracts.CatalogReference>();
      for (var profile : profiles) {
        Objects.requireNonNull(profile, "Operation resource profile entries must not be null");
        if (!seen.add(profile.catalog()))
          throw new IllegalArgumentException(
              "Duplicate catalog reference: "
                  + profile.catalog().catalogId()
                  + ":"
                  + profile.catalog().catalogVersion());
      }
    }
  }

  public record Publish(long expectedVersion, Profiles profiles) {
    public Publish {
      if (expectedVersion < 0 || profiles == null)
        throw new IllegalArgumentException("Expected version and profiles are required");
    }
  }
}
