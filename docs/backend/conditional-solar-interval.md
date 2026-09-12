# Conditional temporal solar-elevation bound

`ConditionalSolarInterval` extends the instantaneous rectangular spatial lower bound across
a time interval using explicit rate and evaluation-error assumptions. `rectangularInterval`
on the Orekit predictor evaluates the same pinned-frame analytical solar model at each cell's
midpoint, retaining every sample, cell boundary and assumption reference.

For the true spatial lower-bound function f, assume |f(t)-f(s)| <= L|t-s| and that its computed
sample overestimates f by at most E. A cell centered at m therefore has lower bound
`computed_f(m) - E - L * max(m-start, end-m)`. Taking the minimum over adjacent cells covers
the whole requested interval, including the final remainder. Elementary floating-point
subtractions/products are rounded outward in the conservative direction. The supplied error
must also cover spatial evaluation and time/frame conversion error; this helper does not
derive such an error qualification.

L is the rate bound of the **complete spatial lower-bound function**, including the parallax
term, not merely Earth's rotation rate. E is positive and explicit. Neither is inferred from
samples, supplied by a hidden default, or claimed to be established by the integration test.
The assumption reference is mandatory. Without independently justified assumptions the result
is conditional simulation evidence, not proof of physical illumination or a validated Planning
gate. No public API or Mission Definition assumption publisher is added in this step.

The calculation permits intervals up to 24 hours, steps up to 60 seconds and at most 4096
samples. Over-budget requests are rejected before evaluation. Nonfinite inputs/samples or
unrepresentable arithmetic are rejected; no partial evidence is returned. This bound does
not use event-root sampling and does not depend on finding every threshold crossing.

Tests include a triangular function whose midpoint is bright while its interval ends are dark,
an independently differentiated sinusoid, complete cell coverage with a remainder, invalid
input/budget rejection, and a pinned-Orekit integration comparison with 111 solar samples.
That finite integration comparison is a regression test, not a proof of the assumed rate/error.

The focused spatial/temporal/illumination run passed 18 tests across four classes with no
failures/errors/skips (`2026-09-12`, local log `/private/tmp/msc-solar-interval.log`). The
new adapter method is not yet exposed through a deployed API or used to commit schedules.
