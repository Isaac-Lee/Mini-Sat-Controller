# Synthetic staring-camera projection

`StareCameraProjection` implements the numerical geometry for
`STARE_TARGET_TANGENT_PLANE_V1`. It projects a uniform rectilinear focal-plane raster onto
the fixed target's WGS84 geodetic tangent plane. All ground coordinates returned by this
helper are metres east/north in that plane. They are not a georeferenced terrain product.

The boresight points from the satellite to the target. The along-axis is target geodetic
north projected perpendicular to that boresight; across is `along × boresight`, so
`across × along = boresight`. A boresight within one degree of either direction of the
north axis is rejected. This third orientation constraint is an explicit synthetic camera
law; it is not inferred spacecraft attitude.

## Projection and pixel spacing

Let `S` be the satellite position relative to the target, `n` the target geodetic normal,
`h = n·S`, and `b`, `a`, `l` the boresight, across and along unit vectors. Focal-plane
coordinates range over `x = ±tan(halfAngleAcross)` and `y = ±tan(halfAngleAlong)`.
The ray direction and plane intersection are:

```
d(x,y) = b + x*a + y*l
Q(x,y) = S - h*d(x,y)/(n·d(x,y))
```

This intersection uses the actual geodetic plane normal, without dividing a nominal swath
or GSD by a radial off-nadir cosine. Raster boundaries are equally spaced in `x` and `y`,
not equally spaced in angle. The target is at the raster centre. All focal-plane corner
rays must point toward the plane with a denominator margin; otherwise the helper rejects
the projection instead of returning an unbounded footprint.

The four corner intersections form the planar footprint. Sutherland–Hodgman clipping against
a caller-provided rectangle in this same plane computes intersection area divided by the
rectangle area. Callers must explicitly construct that chart; geographic AOI coordinates
cannot be passed as metres.

The reported maximum pixel-axis spacing is a conservative bound over the **whole raster at
this instant**, not a value sampled at its centre. For example:

```
dQ/dx = -h * (a*(n·d) - d*(n·a)) / (n·d)^2
```

Its numerator is independent of `x` and affine in `y`, so the maximum numerator norm is
bounded by the two `y` endpoints. The minimum denominator magnitude occurs at a focal-plane
corner. Combining those bounds and multiplying by one pixel's `x` step bounds every
horizontal pixel edge by integration. The corresponding `y` derivative bounds every vertical
edge. The reported GSD bound is the larger axis bound; it is not a diagonal pixel diameter.

## Verification and limits

Tests compare nadir extent, area and altitude scaling with hand calculations; verify full,
quarter, half, empty and boundary-contact rectangle coverage; compare every pixel boundary
in an oblique fixture with independently expanded ray equations; and check that its pixel
edges stay under the analytic bound. Additional cases distinguish geodetic normal from
radial nadir at mid-latitudes, reject hidden/horizon-crossing geometry, and test both
directions of the north-axis degeneracy threshold.

This helper has no API, persistence or operational authority. It does not convert a curved
geographic AOI to the tangent plane, assess terrain/optics/jitter, evaluate an exposure interval,
or promote a Planning gate. Those remain part of the camera-model and Planning integration.

The six focused tests passed with zero failures, errors or skips at 17:10:01 KST on
2026-09-12. Opus independently reviewed the basis, plane intersection, analytic derivative
bound and clipping. The helper uses the same Orekit WGS84 constants as the existing
reference-frame adapter. Local evidence is `/private/tmp/msc-stare-projection-tests.log`
and `.local/stare-projection-verification.json`. No deployed service integration is claimed.
