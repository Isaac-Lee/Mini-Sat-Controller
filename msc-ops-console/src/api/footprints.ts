import { request } from './client'
import { apiProblem, loadPlanning } from './workflow'
import type { Area, MissionInstant } from './types'

// Flight Dynamics sampled camera footprints (CameraFootprintEvaluationContracts). Corners are
// metres east/north of the pointing target in the declared WGS84_LOCAL_LINEAR_RECTANGLE_V1
// chart, which is an approximation: drawing them on a map is SIMULATION evidence only.
export const AOI_MAPPING = 'WGS84_LOCAL_LINEAR_RECTANGLE_V1'
export const FOOTPRINT_SCOPE = 'SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE'

export interface AoiPlanarMapping {
  mapping: string
  environment: string
  approximationNote: string
  centerLatitudeDegrees: number
  centerLongitudeDegrees: number
  widthMeters: number
  heightMeters: number
}
interface Manifest {
  id: string
  query: { area: Area }
  aoiMapping: AoiPlanarMapping
  scope: string
}
interface Sample {
  instant: MissionInstant
  outcome: 'COMPUTED' | 'UNEVALUATED'
  footprintCorners?: Array<{ eastMeters: number; northMeters: number }> | null
  coverageFraction?: number | null
}
interface Result {
  id: string
  samples: Sample[]
  scope: string
}

export interface FootprintPolygon {
  candidateId: string
  instant: MissionInstant
  coverageFraction: number | null
  corners: [number, number][] // [latitude, longitude] degrees
}
export interface FootprintOverlay {
  evaluationId: string
  approximationNote: string
  polygons: FootprintPolygon[]
}

// Inverts the declared linear chart exactly: Flight Dynamics computes widthMeters/heightMeters
// from the query AOI's degree span with one fixed scale per axis, so metres-per-degree is that
// ratio. No separate Earth model is introduced here.
export function toLatLon(
  point: { eastMeters: number; northMeters: number },
  mapping: AoiPlanarMapping,
  area: Area,
): [number, number] {
  const eastPerDegree = mapping.widthMeters / (area.east - area.west)
  const northPerDegree = mapping.heightMeters / (area.north - area.south)
  return [
    mapping.centerLatitudeDegrees + point.northMeters / northPerDegree,
    mapping.centerLongitudeDegrees + point.eastMeters / eastPerDegree,
  ]
}

// ponytail: shows the newest planning run only and caps polygons per overlay; page or pick runs
// in the map if operators need older runs or every sample.
const MAX_POLYGONS = 200
const id = encodeURIComponent

export async function loadFootprints(requestId: string): Promise<{ overlays: FootprintOverlay[]; failures: string[] }> {
  const { runs } = await loadPlanning(requestId)
  const run = runs[runs.length - 1]
  if (!run) return { overlays: [], failures: [] }
  const work = await request<{ camera_model_version?: number }>(`/fe-api/planning/api/planning/runs/${id(run.id)}/camera-work`)
  const version = work.camera_model_version
  if (!version || !Number.isSafeInteger(version) || version < 1) return { overlays: [], failures: [] }
  const assessment = await request<{ body: { candidates: Array<{ candidateId: string; cameraResult: { id?: string } }> } }>(
    `/fe-api/planning/api/planning/runs/${id(run.id)}/camera/${version}`,
  )
  const overlays: FootprintOverlay[] = []
  const failures: string[] = []
  for (const candidate of assessment.body.candidates) {
    const evaluationId = candidate.cameraResult?.id
    if (!evaluationId) continue
    try {
      const base = `/fe-api/flight/api/camera-footprint-evaluations/${id(evaluationId)}`
      const [manifest, result] = await Promise.all([request<Manifest>(base), request<Result>(`${base}/result`)])
      const mapping = manifest.aoiMapping
      if (mapping.mapping !== AOI_MAPPING || mapping.environment !== 'SIMULATION' || result.scope !== FOOTPRINT_SCOPE || result.id !== evaluationId) {
        failures.push(`${candidate.candidateId}: 지원하지 않는 투영 근거라 표시하지 않았습니다.`)
        continue
      }
      const polygons = result.samples
        .filter((s) => s.outcome === 'COMPUTED' && s.footprintCorners && s.footprintCorners.length >= 3)
        .slice(0, MAX_POLYGONS)
        .map((s) => ({
          candidateId: candidate.candidateId,
          instant: s.instant,
          coverageFraction: s.coverageFraction ?? null,
          corners: s.footprintCorners!.map((c) => toLatLon(c, mapping, manifest.query.area)),
        }))
      overlays.push({ evaluationId, approximationNote: mapping.approximationNote, polygons })
    } catch (error) {
      failures.push(`${candidate.candidateId}: ${apiProblem(error)}`)
    }
  }
  return { overlays, failures }
}
