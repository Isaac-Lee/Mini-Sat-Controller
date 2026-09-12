# Simulator onboard execution

Status: completion-effect arithmetic is implemented and tested. Durable receipt/execution
records, command-clock advancement and delivery/reconciliation remain in progress. This is
an incremental part of the full spacecraft and ground-station simulator requirements.

`SimulatorOperationEffects` consumes a resolved catalog, matching operation-resource profile,
pinned mission capacities and immutable initial reservoirs. It validates the exact catalog
identity/version, operation and resource figures before calculating any effect. The caller
must resolve and preserve the actual source versions and spacecraft/mission bindings; the
calculator alone does not prove those cross-service bindings or release authority.

At modeled completion, IMAGE adds the declared generated megabytes. DOWNLINK first adds any
declared housekeeping data, then removes at most the available storage using the explicitly
published rate multiplied by catalog duration. Both operations consume the catalog's declared
propellant. A production-before-drain capacity checkpoint is enforced, so a DOWNLINK cannot
hide an intermediate overflow merely because its final storage would be lower. This is a
defined checkpoint in an atomic-completion model, not a continuous physical peak estimate.

Invalid initial reservoirs or mismatched evidence are rejected. Unsupported MANEUVER,
nonfinite arithmetic, storage overflow and insufficient propellant return an unchanged state
and no partially applied production, drain or burn. A successful result is `APPLIED`, not a
ground-observed command `EXECUTED` assertion. The future executor must persist the effect
and command ledger together, with duplicate suppression, ordered completion and restart proof.

The result retains declared power consumption but does not yet evolve a battery. Battery,
orbital and attitude evolution remain required follow-up implementation, using explicit
simulation models rather than inferred SPACEEYE-T1 hardware specifications. A modeled drain
is neither ground reception evidence nor authorization to delete durable payload content.

Six focused Maven tests passed (`SimulatorOperationEffectsTest`, log
`/private/tmp/msc-simulator-effects.log`): IMAGE-to-DOWNLINK sequence and absence of future drain
credit, peak overflow, propellant rejection for both operations, unsupported maneuver,
invalid initial state/evidence mismatch, and finite-input arithmetic overflow. This is pure
calculation verification; no new API or deployed command execution is claimed.
