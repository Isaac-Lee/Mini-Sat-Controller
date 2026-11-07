# Simulation and replay

Simulation is a first-class requirement. Real and simulator adapters implement the
same ports; application/domain code must never branch on a simulator-specific SDK.

| Port | Current implementation | Future real / simulation counterparts |
| --- | --- | --- |
| Clock | VirtualClock, explicitly advanced TAI | Validated RealClock / ReplayClock / AcceleratedClock |
| ScheduleRepository | Atomic in-memory immutable history | Transactional durable adapter / isolated scenario store |
| ReferenceDataRepository | Contract, fixture inputs supplied directly in proof | Validating snapshot collector/store / recorded approved snapshots |
| SpacecraftLinkPort | Contract only | Space Link adapter / Sat-Simulator |
| GroundStationPort | Contract only | Station integration / antenna, tracking, RF and modem simulator |
| ObjectStoragePort | Streaming contract only | Object storage / scenario manifest storage |
| OrbitComputationPort | Contract only | Validated numerical implementation / deterministic oracle |

The JUnit composition root wires PlanObservation with VirtualClock and
InMemoryScheduleRepository. DaejeonAOI, one approved activity, input references and
feasibility are explicit fixtures. No real network, geocoding, weather, orbit
calculation or commanding is invoked. Concurrency tests exercise the same repository
contract's expected-version semantics, not timing sleeps.

Scenario Engine will control clock advancement and scheduled stimuli. Record/Replay
must retain original observations, arrival/observation times, order, quality,
versioned input manifests, IDs and causation. Fault Injection should cover delayed,
duplicated and out-of-order telemetry, lost contacts, partial payload reception,
onboard/ground divergence and stale planning inputs. Replay ordering policy and
seed handling must be explicit before claims of reproducibility across simulators.

A first `TOY_MODEL` slice exists (ADR-0008 amendment): the simulator service integrates
battery Wh, storage MB and propellant kg per tick from declared operation effects, derives a
link-available flag from supplied TAI visibility windows with seeded dropouts, and records a
seeded event log whose replay reproduces identical telemetry. Seed handling is explicit (one
`java.util.Random(seed)` drawn per step in tick order, stored in the log); ordering is by
completion tick then command ID. This is labelled toy fidelity, not the Record/Replay of real
observations described above.

Shadow Planning must write to a separate schedule authority/store and cannot call
real release adapters. Command release remains a safety/authority boundary in
simulation too. Simulator fidelity, physical validation and fault recovery are
not established by the foundation test. No complete simulator is implemented.
