import { describe, expect, it } from 'vitest'
import { toLatLon, type AoiPlanarMapping } from './footprints'
import type { Area } from './types'

// Forward law copied from CameraFootprintEvaluationApi (WGS84 radii at the target latitude).
function forward(area: Area, altitudeMeters: number): AoiPlanarMapping {
  const a = 6378137
  const f = 1 / 298.257223563
  const e2 = f * (2 - f)
  const lat = (area.south + area.north) / 2
  const phi = (lat * Math.PI) / 180
  const d = 1 - e2 * Math.sin(phi) ** 2
  const n = a / Math.sqrt(d)
  const m = (a * (1 - e2)) / d ** 1.5
  const rad = Math.PI / 180
  return {
    mapping: 'WGS84_LOCAL_LINEAR_RECTANGLE_V1',
    environment: 'SIMULATION',
    approximationNote: 'note',
    centerLatitudeDegrees: lat,
    centerLongitudeDegrees: (area.west + area.east) / 2,
    widthMeters: (n + altitudeMeters) * Math.cos(phi) * (area.east - area.west) * rad,
    heightMeters: (m + altitudeMeters) * (area.north - area.south) * rad,
  }
}

describe('toLatLon', () => {
  const area: Area = { id: 'a', west: 127.25, south: 36.2, east: 127.55, north: 36.5, sourceReference: 's' }

  it('maps the chart rectangle corners back onto the query AOI corners', () => {
    const mapping = forward(area, 120)
    const ne = toLatLon({ eastMeters: mapping.widthMeters / 2, northMeters: mapping.heightMeters / 2 }, mapping, area)
    const sw = toLatLon({ eastMeters: -mapping.widthMeters / 2, northMeters: -mapping.heightMeters / 2 }, mapping, area)
    expect(ne[0]).toBeCloseTo(area.north, 9)
    expect(ne[1]).toBeCloseTo(area.east, 9)
    expect(sw[0]).toBeCloseTo(area.south, 9)
    expect(sw[1]).toBeCloseTo(area.west, 9)
  })

  it('maps the pointing target (origin) to the AOI centre', () => {
    const mapping = forward(area, 0)
    expect(toLatLon({ eastMeters: 0, northMeters: 0 }, mapping, area)).toEqual([36.35, 127.4])
  })
})
