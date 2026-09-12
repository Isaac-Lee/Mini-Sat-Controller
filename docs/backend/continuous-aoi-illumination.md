# Continuous AOI illumination work

Status: the instantaneous spatial calculation exists; temporal qualification and Planning
integration remain incomplete. Existing `SAMPLED_POINTS_ONLY` results retain their meaning.

`RectangularSolarElevation` finds the minimum geocentric solar elevation across every point
of a nonwrapping geodetic rectangle. It asks the solar provider for a position in the exact
body frame of the supplied oblate ellipsoid. For geodetic latitude `φ`, longitude `λ` and
unit Sun vector `(sx, sy, sz)`, the normal dot product is

```text
n · s = cos(φ) [sx cos(λ) + sy sin(λ)] + sz sin(φ).
```

Because `cos(φ)` is nonnegative on the supported latitude interval, longitude can be minimized
first, independently of latitude. Evaluate the longitude endpoints and any enclosed anti-solar
longitude, then evaluate latitude endpoints and enclosed stationary points. This finds an
interior minimum that a centre-plus-corners sample can miss.

The topocentric elevation is bounded below by

```text
asin(minimum normal dot product) - asin(maximum radius / Sun distance).
```

Here maximum radius is the equatorial radius plus the absolute altitude: the triangle inequality
also covers below-ellipsoid targets. The Sun must lie outside this enclosing sphere. Clamp the
final elevation to -π/2, the geometric minimum. Inputs reject nonfinite values, unsupported
latitude/altitude bounds, wrapping or empty rectangles, and non-oblate Earth models.

This evaluates a mathematical bound in floating point relative to the supplied solar model.
It does not supply a certified rounding margin, ephemeris error bound, temporal angular-rate
bound or complete illuminated interval. Dense-grid tests corroborate the implementation but do
not turn sampling into mathematical proof. Before a consumer can accept a complete interval it
must account for those remaining errors and gaps, and retain unresolved intervals explicitly.

Four tests passed through isolated javac and direct JUnit assertion invocation: an interior
minimum missed by five points, 40,344 grid comparisons against Orekit geodetic normals and
topocentric directions, longitude-boundary/polar-Sun cases, and invalid geometry. The provider
test also asserts the requested frame is the ellipsoid frame. These tests subsequently passed
in the 2026-09-12 Mission Definition / Flight Dynamics Maven reactor (207 total tests,
zero failures, errors or skips; `/private/tmp/msc-gp-correlation-reactor.log`).
No API or candidate feasibility gate uses this helper yet.

The result retains its exact evaluation epoch to prevent an instantaneous value being mistaken
for interval coverage. Latitude is explicitly geodetic, longitude east-positive and altitude
ellipsoid-relative. The 89-degree latitude limit matches the existing target-illumination API's
supported input range; it is not a claim that the mathematical bound fails at higher latitudes.
Terrain variation must be covered by an explicit altitude envelope in any future extension;
its effect must not be discarded merely because solar parallax is small.
