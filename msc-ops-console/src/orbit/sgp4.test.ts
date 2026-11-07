import { describe, expect, it } from 'vitest'
import type { MeanElements } from '../api/types'
import { findAccessWindows, lookGeometry, satrecFromElements, subSatellitePoint, sunElevationDegrees } from './sgp4'

// Sun-synchronous ~505 km orbit (15.2 rev/day), epoch fixed so the test is deterministic.
const ELEMENTS: MeanElements = {
  noradId: 63229, name: 'TEST', internationalDesignator: '25001A', epochUtc: '2026-11-07T00:00:00',
  meanMotionRevolutionsPerDay: 15.2, eccentricity: 0.0001, inclinationDegrees: 97.4, ascendingNodeDegrees: 120,
  pericenterDegrees: 90, meanAnomalyDegrees: 270, bstar: 0, elementSetNumber: 999, revolutionNumber: 10, classification: 'U',
}
const DAEJEON = { latitudeDegrees: 36.35, longitudeDegrees: 127.38, altitudeMeters: 0 }
const EPOCH = Date.parse('2026-11-07T00:00:00Z')
const CRITERIA = { minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 }
const satrec = satrecFromElements(ELEMENTS)

describe('browser SGP4', () => {
  it('stays on a ~505 km orbit bounded by its inclination', () => {
    for (let ms = EPOCH; ms < EPOCH + 6_000_000; ms += 60_000) {
      const p = subSatellitePoint(satrec, ms)
      expect(p.altitudeMeters / 1000).toBeGreaterThan(480)
      expect(p.altitudeMeters / 1000).toBeLessThan(560)
      expect(Math.abs(p.latitudeDegrees)).toBeLessThanOrEqual(180 - 97.4 + 0.1)
    }
  })

  it('finds imaging windows whose edges and best point respect the criteria', () => {
    const windows = findAccessWindows(satrec, DAEJEON, EPOCH, EPOCH + 7 * 86_400_000, CRITERIA)
    expect(windows.length).toBeGreaterThanOrEqual(2)
    for (const w of windows) {
      const duration = (w.endMs - w.startMs) / 1000
      expect(duration).toBeGreaterThan(0)
      expect(duration).toBeLessThan(400)
      const best = lookGeometry(satrec, DAEJEON, w.bestMs)
      expect(best.offNadirDegrees).toBeLessThanOrEqual(30)
      expect(best.elevationDegrees).toBeGreaterThanOrEqual(10)
      // Just outside each edge at least one criterion fails.
      for (const outside of [w.startMs - 2_000, w.endMs + 2_000]) {
        const g = lookGeometry(satrec, DAEJEON, outside)
        expect(g.elevationDegrees < 10 || g.offNadirDegrees > 30).toBe(true)
      }
    }
  })

  // Reference passes from an independent implementation (python-sgp4 2.24 with WGS-72, its own
  // IAU-82 GMST rotation and WGS-84 geodetic), so a dropped or wrong frame rotation fails here.
  it('matches an independent SGP4 implementation for the Daejeon passes', () => {
    const windows = findAccessWindows(satrec, DAEJEON, EPOCH, EPOCH + 7 * 86_400_000, CRITERIA)
    const reference = [
      ['2026-11-09T20:05:14Z', 81],
      ['2026-11-11T08:53:43Z', 71],
      ['2026-11-12T08:36:03Z', 49],
    ] as const
    for (const [start, seconds] of reference) {
      const match = windows.find((w) => Math.abs(w.startMs - Date.parse(start)) <= 2_000)
      expect(match, start).toBeDefined()
      expect(Math.abs((match!.endMs - match!.startMs) / 1000 - seconds)).toBeLessThanOrEqual(2)
    }
  })

  it('never reports a window past a horizon shorter than one scan step', () => {
    const [w] = findAccessWindows(satrec, DAEJEON, EPOCH, EPOCH + 7 * 86_400_000, CRITERIA)
    const inside = findAccessWindows(satrec, DAEJEON, w.startMs + 1_000, w.startMs + 4_000, CRITERIA)
    expect(inside).toHaveLength(1)
    expect(inside[0].endMs).toBeLessThanOrEqual(w.startMs + 4_000)
  })

  it('relates off-nadir to elevation as spherical geometry requires', () => {
    const [w] = findAccessWindows(satrec, DAEJEON, EPOCH, EPOCH + 7 * 86_400_000, CRITERIA)
    const g = lookGeometry(satrec, DAEJEON, w.bestMs)
    const radius = 6_371 + subSatellitePoint(satrec, w.bestMs).altitudeMeters / 1000
    // sin(off-nadir) = R_earth * cos(elevation) / r_sat; the ellipsoid makes this approximate.
    const expected = Math.asin((6_371 * Math.cos((g.elevationDegrees * Math.PI) / 180)) / radius) * (180 / Math.PI)
    expect(Math.abs(g.offNadirDegrees - expected)).toBeLessThan(1)
  })

  it('puts the sun near 37° at Daejeon solar noon in early November and below the horizon at midnight', () => {
    expect(sunElevationDegrees(DAEJEON, Date.parse('2026-11-07T03:30:00Z'))).toBeGreaterThan(35)
    expect(sunElevationDegrees(DAEJEON, Date.parse('2026-11-07T03:30:00Z'))).toBeLessThan(40)
    expect(sunElevationDegrees(DAEJEON, Date.parse('2026-11-07T15:30:00Z'))).toBeLessThan(-40)
  })
})
