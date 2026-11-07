// Browser-side SGP4 used by the demo backend in place of Flight Dynamics (Orekit). Live mode never
// uses this file: live predictions always come from the Flight Dynamics service.
//
// Frames and units: SGP4 outputs TEME position (km); it is rotated to Earth-fixed with GMST and
// converted to geodetic with the library's WGS-72 constants. Angles are degrees at this module's
// boundary and radians inside satellite.js. Times are UTC epoch milliseconds; SGP4 earth rotation
// needs UT1 and UTC stands in for it (|UT1-UTC| < 0.9 s, under ~0.5 km of ground motion).
import {
  degreesLat,
  degreesLong,
  ecfToLookAngles,
  eciToEcf,
  eciToGeodetic,
  geodeticToEcf,
  gstime,
  jday,
  json2satrec,
  propagate,
  sunPos,
  type GeodeticLocation,
  type SatRec,
} from 'satellite.js'
import type { MeanElements } from '../api/types'

const DEG = Math.PI / 180
const AU_KM = 149_597_870.7

export interface Site {
  latitudeDegrees: number
  longitudeDegrees: number
  altitudeMeters: number
}

export interface AccessCriteria {
  minimumElevationDegrees: number
  maximumOffNadirDegrees: number
  minimumDurationSeconds: number
}

export interface AccessWindow {
  startMs: number
  endMs: number
  /** Smallest off-nadir angle reached inside the window (best imaging geometry). */
  bestOffNadirDegrees: number
  bestMs: number
}

export function satrecFromElements(e: MeanElements): SatRec {
  return json2satrec({
    OBJECT_NAME: e.name,
    OBJECT_ID: e.internationalDesignator,
    EPOCH: e.epochUtc,
    MEAN_MOTION: e.meanMotionRevolutionsPerDay,
    ECCENTRICITY: e.eccentricity,
    INCLINATION: e.inclinationDegrees,
    RA_OF_ASC_NODE: e.ascendingNodeDegrees,
    ARG_OF_PERICENTER: e.pericenterDegrees,
    MEAN_ANOMALY: e.meanAnomalyDegrees,
    NORAD_CAT_ID: e.noradId,
    ELEMENT_SET_NO: e.elementSetNumber,
    REV_AT_EPOCH: e.revolutionNumber,
    BSTAR: e.bstar,
    MEAN_MOTION_DOT: 0,
    MEAN_MOTION_DDOT: 0,
  })
}

const geodetic = (site: Site): GeodeticLocation => ({
  latitude: site.latitudeDegrees * DEG,
  longitude: site.longitudeDegrees * DEG,
  height: site.altitudeMeters / 1000,
})

function earthFixed(satrec: SatRec, ms: number) {
  const date = new Date(ms)
  const state = propagate(satrec, date)
  if (!state) throw new Error(`SGP4 propagation failed at ${date.toISOString()} (error ${satrec.error})`)
  const gmst = gstime(date)
  return { ecf: eciToEcf(state.position, gmst), geo: eciToGeodetic(state.position, gmst) }
}

export function subSatellitePoint(satrec: SatRec, ms: number) {
  const { geo } = earthFixed(satrec, ms)
  return { latitudeDegrees: degreesLat(geo.latitude), longitudeDegrees: degreesLong(geo.longitude), altitudeMeters: geo.height * 1000 }
}

/** Target elevation seen from the site, and the satellite's off-nadir angle to the target. */
export function lookGeometry(satrec: SatRec, site: Site, ms: number) {
  const { ecf } = earthFixed(satrec, ms)
  const observer = geodetic(site)
  const elevationDegrees = ecfToLookAngles(observer, ecf).elevation / DEG
  const target = geodeticToEcf(observer)
  const los = { x: target.x - ecf.x, y: target.y - ecf.y, z: target.z - ecf.z }
  const cos = -(los.x * ecf.x + los.y * ecf.y + los.z * ecf.z) / (Math.hypot(los.x, los.y, los.z) * Math.hypot(ecf.x, ecf.y, ecf.z))
  // Nadir is taken as geocentric (towards Earth's centre), the same simplification as a spherical Earth.
  return { elevationDegrees, offNadirDegrees: Math.acos(Math.min(1, Math.max(-1, cos))) / DEG }
}

export function sunElevationDegrees(site: Site, ms: number): number {
  const date = new Date(ms)
  const [x, y, z] = sunPos(jday(date)).rsun // AU, ECI (mean equator of date)
  const sunEcf = eciToEcf({ x: x * AU_KM, y: y * AU_KM, z: z * AU_KM }, gstime(date))
  return ecfToLookAngles(geodetic(site), sunEcf).elevation / DEG
}

// Positive inside a window: both the elevation mask and the off-nadir limit hold, matching the
// Flight Dynamics POINT_IMAGING margin (OrekitAccessPredictor).
function margin(satrec: SatRec, site: Site, c: AccessCriteria, ms: number) {
  const g = lookGeometry(satrec, site, ms)
  return Math.min(g.elevationDegrees - c.minimumElevationDegrees, c.maximumOffNadirDegrees - g.offNadirDegrees)
}

const SCAN_STEP_MS = 10_000
const ROOT_TOLERANCE_MS = 1_000

/**
 * Imaging windows for a ground point between startMs and endMs.
 * ponytail: fixed 10 s scan, so a window shorter than ~10 s can be missed; use an event detector
 * with derivative-based step control if very short windows matter.
 */
export function findAccessWindows(satrec: SatRec, site: Site, startMs: number, endMs: number, c: AccessCriteria): AccessWindow[] {
  const inside = (ms: number) => margin(satrec, site, c, ms) > 0
  const edge = (outMs: number, inMs: number) => {
    while (Math.abs(inMs - outMs) > ROOT_TOLERANCE_MS) {
      const mid = (outMs + inMs) / 2
      if (inside(mid)) inMs = mid
      else outMs = mid
    }
    return Math.round(inMs)
  }
  const windows: AccessWindow[] = []
  let open: number | null = inside(startMs) ? startMs : null
  let previous = startMs
  for (let ms = Math.min(startMs + SCAN_STEP_MS, endMs); ; ms = Math.min(ms + SCAN_STEP_MS, endMs)) {
    const now = inside(ms)
    if (open === null && now) open = edge(previous, ms)
    if (open !== null && (!now || ms === endMs)) {
      const close = now ? endMs : edge(ms, previous)
      if ((close - open) / 1000 >= c.minimumDurationSeconds) windows.push({ startMs: open, endMs: close, ...bestPoint(satrec, site, open, close) })
      open = null
    }
    if (ms === endMs) break
    previous = ms
  }
  return windows
}

function bestPoint(satrec: SatRec, site: Site, startMs: number, endMs: number) {
  let best = { bestOffNadirDegrees: Infinity, bestMs: startMs }
  for (let ms = startMs; ms <= endMs; ms += 1_000) {
    const off = lookGeometry(satrec, site, ms).offNadirDegrees
    if (off < best.bestOffNadirDegrees) best = { bestOffNadirDegrees: off, bestMs: ms }
  }
  return best
}
