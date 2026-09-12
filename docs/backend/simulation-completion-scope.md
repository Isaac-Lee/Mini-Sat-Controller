# Completion scope for the accepted simulator backend

The accepted backend goal is a complete operational flow using real APIs, owned persistent
storage and independently deployable services, with external spacecraft and ground stations
represented by simulators. It does not require unavailable physical SPACEEYE-T1 hardware
qualification before the simulator workflow can operate.

A simulation-only scheduling, release or quality result may be accepted when it is actually
computed under an explicit, versioned, approved simulation model and remains bound to that
model and environment. This does not grant real spacecraft command authority or establish
actual sensor performance. The existing service ownership and approval boundaries remain.

Conversely, merely labeling something SIMULATION does not establish that it was evaluated.
Point-target access is not an AOI footprint. Required line-of-sight pointing is not an executed
attitude. A byte preview is not a georeferenced observation. A downloaded source package is
not evidence that a particular request's coverage/cloud criteria were satisfied.

## Next dependencies toward the complete flow

1. Compute required target-pointing geometry from exact owned orbit evidence and pinned Earth
   orientation data. Retain coordinate conventions and bounded sample semantics; do not infer
   a complete attitude quaternion from a line-of-sight vector.
2. Publish an explicit simulation footprint/pointing law before evaluating sensor coverage.
   The existing swath width alone does not specify along-track extent or scan/stare behavior.
   In particular, pointing continuously at a fixed ground target does not produce a moving
   along-track boresight intercept.
3. Evaluate the supported simulation coverage and attitude-transition model against each
   proposed activity sequence, then combine it with resources, illumination, weather,
   reservation/current safety and schedule-conflict checks before atomic commitment.
4. Bind approved commands to that commitment and enforce the simulation release/dispatch
   workflow through Space Link. Retain reconciliation and UNKNOWN outcomes across faults.
5. Bind simulated acquisition/product geometry and quality evidence to the request revision,
   compute its criteria and only then update partial/complete fulfillment and projections.

This is a dependency clarification, not a reduction of the backend goal. Full request-to-product
execution, simulator faults/restarts, selective scaling and evidence-backed fulfillment remain
required. Actual hardware qualification remains outside this accepted simulator scope.
