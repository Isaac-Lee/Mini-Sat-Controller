// In-browser fake of the MSC backend for demo mode. Every response is synthetic and lives only in
// this tab's memory; nothing reaches a real service, database or broker. Shapes follow the real
// APIs closely enough that the normal console screens render them unchanged.
import type {
  AccessQuery,
  Area,
  EphemerisDocument,
  GroundPoint,
  MissionInstant,
  OrbitSnapshot,
  RequestState,
  RequestStatus,
  SafetyLatchState,
  Submission,
} from '../api/types'
import { jday, sunPos } from 'satellite.js'
import { findAccessWindows, satrecFromElements, subSatellitePoint, sunElevationDegrees, type AccessCriteria, type Site } from '../orbit/sgp4'

const NORAD_ID = 63229
const CRAFT = 'sim-spaceeye-t1'
const TAI_MINUS_UTC_S = 37
const STAGE_MS = { RECEIVED: 4_000, ACCEPTED: 8_000, SCHEDULED: 8_000 }

export const DEMO_TLE_URL =
  'data:text/plain,' +
  encodeURIComponent('SPACEEYE-T1 (DEMO)\n1 63229U 25001A   26311.50000000  .00000000  00000-0  00000-0 0  9990\n2 63229  97.4000 120.0000 0001000  90.0000 270.0000 15.20000000 00010\n')

const DEMO_PREVIEW_URL =
  'data:image/svg+xml,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="120"><defs><linearGradient id="g"><stop offset="0" stop-color="#3b5d3a"/><stop offset="1" stop-color="#9fb58a"/></linearGradient></defs><rect width="240" height="120" fill="url(#g)"/><text x="12" y="64" fill="#fff" font-family="sans-serif" font-size="14">DEMO 합성 진단 이미지</text></svg>',
  )
const DEMO_CONTENT_URL = 'data:application/octet-stream,' + encodeURIComponent('MSC demo synthetic payload — not real data\n')

export function demoSourceUrl(preview: boolean): string {
  return preview ? DEMO_PREVIEW_URL : DEMO_CONTENT_URL
}

// ---- call log (shown under the tabs so a viewer sees which service each click reaches) ----

export interface DemoCall {
  seq: number
  service: string
  method: string
  path: string
  status: number
}
const SERVICE_NAME: Record<string, string> = {
  reference: 'Reference Data',
  flight: 'Flight Dynamics',
  tasking: 'Tasking',
  planning: 'Planning',
  control: 'Spacecraft Control',
  product: 'Product',
  monitoring: 'Monitoring',
  anomaly: 'Anomaly',
}
let calls: DemoCall[] = []
const listeners = new Set<(calls: DemoCall[]) => void>()
export function subscribeDemoCalls(listener: (calls: DemoCall[]) => void): () => void {
  listeners.add(listener)
  listener(calls)
  return () => listeners.delete(listener)
}

// ---- helpers ----

const tai = (ms: number): MissionInstant => ({ seconds: Math.floor(ms / 1000) + TAI_MINUS_UTC_S, nanos: (ms % 1000) * 1e6, scale: 'TAI' })
const taiMs = (i: MissionInstant) => (i.seconds - TAI_MINUS_UTC_S) * 1000 + i.nanos / 1e6
const sha = (seed: string) => {
  let h = 2166136261
  let out = ''
  for (let round = 0; round < 8; round++) {
    for (const c of seed + round) h = Math.imul(h ^ c.charCodeAt(0), 16777619)
    out += (h >>> 0).toString(16).padStart(8, '0')
  }
  return out
}
class HttpError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}
const fail = (status: number, message: string): never => {
  throw new HttpError(status, message)
}

// ---- reference data and orbit ----

const PLACES: Array<{ id: string; aliases: string[]; area: Omit<Area, 'id' | 'sourceReference'> }> = [
  { id: 'place-daejeon', aliases: ['대전', 'daejeon'], area: { west: 127.25, south: 36.2, east: 127.55, north: 36.5 } },
  { id: 'place-busan', aliases: ['부산', 'busan'], area: { west: 128.9, south: 35.0, east: 129.3, north: 35.3 } },
  { id: 'place-jeju', aliases: ['제주', 'jeju'], area: { west: 126.15, south: 33.2, east: 126.95, north: 33.55 } },
  { id: 'place-seoul-junggu', aliases: ['중구', 'jung-gu'], area: { west: 126.96, south: 37.54, east: 127.02, north: 37.57 } },
  { id: 'place-daejeon-junggu', aliases: ['중구', 'jung-gu'], area: { west: 127.38, south: 36.25, east: 127.44, north: 36.33 } },
]
const placesFor = (target: string) => {
  const key = target.trim().toLowerCase().replace(/\s+/g, ' ')
  return PLACES.filter((p) => p.aliases.includes(key)).map((p) => ({
    id: p.id,
    version: 1,
    aliases: p.aliases,
    area: { id: `${p.id}:1`, ...p.area, sourceReference: `gazetteer:${p.id}:1` },
    approvalReference: 'DEMO-APPROVAL-1',
  }))
}

const ORBIT_EPOCH_MS = Date.now() - 6 * 3_600_000
// A typical Earth-observation sun-synchronous orbit: descending node at 10:30 local solar time,
// i.e. ascending node at 22:30, which puts the node 157.5° east of the sun's right ascension.
const DEMO_RAAN_DEGREES = ((sunPos(jday(new Date(ORBIT_EPOCH_MS))).rtasc * 180) / Math.PI + 157.5 + 360) % 360

const ORBIT: OrbitSnapshot = {
  id: 'demo-orbit-snapshot-1',
  provider: 'DEMO (CelesTrak 형식 흉내, 실제 수집 아님)',
  sourceUrl: 'demo://celestrak/63229',
  fetchedAt: tai(Date.now() - 3_600_000),
  rawSha256: sha('orbit'),
  elements: {
    noradId: NORAD_ID,
    name: 'SPACEEYE-T1',
    internationalDesignator: '25001A',
    epochUtc: new Date(ORBIT_EPOCH_MS).toISOString().slice(0, 19),
    meanMotionRevolutionsPerDay: 15.2,
    eccentricity: 0.0001,
    inclinationDegrees: 97.4,
    ascendingNodeDegrees: Math.round(DEMO_RAAN_DEGREES * 1e4) / 1e4,
    pericenterDegrees: 90,
    meanAnomalyDegrees: 270,
    bstar: 0,
    elementSetNumber: 999,
    revolutionNumber: 10,
    classification: 'U',
  },
}

// The demo satellite is real SGP4 over made-up (but physically plausible) sun-synchronous elements.
const SATREC = satrecFromElements(ORBIT.elements)
const MODEL = 'SGP4 (브라우저 satellite.js, 데모 궤도 요소)'

function groundTrack(start: MissionInstant, count: number, stepSeconds: number): GroundPoint[] {
  return Array.from({ length: count }, (_, i) => ({
    time: { ...start, seconds: start.seconds + i * stepSeconds },
    ...subSatellitePoint(SATREC, taiMs(start) + i * stepSeconds * 1000),
    referenceDigest: 'demo',
  }))
}

// The demo enforces the same limits as Flight Dynamics (GeneralPerturbationsAdapter and
// OrekitAccessPredictor), so a request the real service would reject fails here too.
const EPOCH_TAI_SECONDS = tai(ORBIT_EPOCH_MS).seconds
function requireEpochValidity(horizon: { start: MissionInstant; end: MissionInstant }, message: string) {
  if ([horizon.start, horizon.end].some((i) => Math.abs(i.seconds - EPOCH_TAI_SECONDS) > 7 * 86_400)) fail(400, message)
}

// Same query the console sends for point imaging (TimelinePanel, ImagingOpportunities).
const IMAGING: AccessCriteria = { minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 }
const MINIMUM_SUN_ELEVATION_DEGREES = 10
const centre = (area: Area): Site => ({ latitudeDegrees: (area.south + area.north) / 2, longitudeDegrees: (area.west + area.east) / 2, altitudeMeters: 0 })

// ---- requests ----

interface DemoRequest {
  id: string
  submissions: Submission[]
  area: Area | null
  options: Area[]
  createdMs: number
  stageStartMs: number
  baseVersion: number
  cancelled: { version: number; atMs: number; planned: boolean } | null
}

function resolveArea(submission: Submission): { area: Area | null; options: Area[] } {
  if (submission.area) return { area: submission.area, options: [] }
  const places = placesFor(submission.target)
  return places.length === 1 ? { area: places[0].area, options: [] } : { area: null, options: places.map((p) => p.area) }
}

// Status advances with wall-clock time to imitate the asynchronous services behind Tasking.
function progress(r: DemoRequest, now: number): { status: RequestStatus; version: number; updatedMs: number; reason: string } {
  if (r.cancelled) return { status: 'CANCELLED', version: r.cancelled.version, updatedMs: r.cancelled.atMs, reason: '요청자 취소' }
  const elapsed = now - r.stageStartMs
  if (!r.area) {
    const at = r.stageStartMs + Math.min(elapsed, STAGE_MS.RECEIVED)
    return elapsed < STAGE_MS.RECEIVED
      ? { status: 'RECEIVED', version: r.baseVersion, updatedMs: r.stageStartMs, reason: '' }
      : { status: 'CLARIFICATION_NEEDED', version: r.baseVersion + 1, updatedMs: at, reason: r.options.length ? '여러 승인 지명이 일치합니다' : '승인 지명을 찾지 못했습니다' }
  }
  let at = r.stageStartMs
  const steps: Array<[RequestStatus, number, string]> = [
    ['RECEIVED', STAGE_MS.RECEIVED, ''],
    ['ACCEPTED', STAGE_MS.ACCEPTED, '영역 확정, 계획 대기'],
    ['SCHEDULED', STAGE_MS.SCHEDULED, '시뮬레이션 일정 v1 확정'],
  ]
  for (const [n, [status, duration, reason]] of steps.entries()) {
    if (now < at + duration) return { status, version: r.baseVersion + n, updatedMs: at, reason }
    at += duration
  }
  return { status: 'FULFILLED', version: r.baseVersion + 3, updatedMs: at, reason: '합성 V1 완료 결과 등록' }
}

function view(r: DemoRequest, now: number): RequestState {
  const p = progress(r, now)
  const s = r.submissions[r.submissions.length - 1]
  return {
    id: r.id,
    version: p.version,
    body: {
      request: {
        id: { value: r.id },
        resolvedAoi: r.area ? { value: r.area.id } : null,
        revision: r.submissions.length,
        fulfillmentCriteria: 'COVERAGE_AND_CLOUD',
        deadline: s.deadline,
        requestedPriority: s.priority,
        interactionPreference: s.preference,
        status: p.status,
      },
      owner: 'requester',
      target: s.target,
      area: r.area,
      criteria: s.criteria ?? { minimumCoverageFraction: 0.8, maximumCloudFraction: 0.3 },
      createdAt: tai(r.createdMs),
      updatedAt: tai(p.updatedMs),
      reason: p.reason,
      clarificationOptions: p.status === 'CLARIFICATION_NEEDED' ? r.options : [],
    },
  }
}

// ---- safety: starts frozen until two operators approve, like a freshly provisioned craft ----

function freshState() {
  const seededAt = Date.now() - 86_400_000
  const seed: DemoRequest = {
    id: 'demo-req-0001',
    submissions: [{ target: '대전', area: null, criteria: null, deadline: null, priority: 50, preference: 'AUTO' }],
    area: placesFor('대전')[0].area,
    options: [],
    createdMs: seededAt,
    stageStartMs: seededAt,
    baseVersion: 1,
    cancelled: null,
  }
  const latch: SafetyLatchState = {
    id: CRAFT,
    version: 1,
    body: { spacecraftId: CRAFT, policyVersion: 1, generation: 1, frozen: true, reasons: ['OPERATOR_ENABLE_REQUIRED'], approvals: [] },
  }
  return {
    requests: [seed],
    imported: false,
    predictions: new Map<string, { start: MissionInstant; end: MissionInstant; stepSeconds: number }>(),
    safety: { history: [latch] },
    seq: 0,
  }
}
let db = freshState()

export function resetDemoBackend() {
  db = freshState()
  calls = []
  listeners.forEach((l) => l(calls))
}

// ---- demo footprints: obviously synthetic 2 km squares stepping across the AOI ----

function demoFootprint(evaluationId: string, now: number) {
  const area = requestFor(owner(evaluationId), now).r.area ?? fail(404, 'no area')
  const rad = Math.PI / 180
  const lat = (area.south + area.north) / 2
  const widthMeters = 6378137 * Math.cos(lat * rad) * (area.east - area.west) * rad
  const heightMeters = 6378137 * (area.north - area.south) * rad
  const aoiMapping = {
    mapping: 'WGS84_LOCAL_LINEAR_RECTANGLE_V1',
    environment: 'SIMULATION',
    approximationNote: 'DEMO 합성 footprint — 실제 투영 계산 결과가 아닙니다.',
    centerLatitudeDegrees: lat,
    centerLongitudeDegrees: (area.west + area.east) / 2,
    widthMeters,
    heightMeters,
  }
  const offset = evaluationId.endsWith('-1') ? -1 : 1
  const samples = [-2, -1, 0, 1, 2].map((k) => {
    const e = k * widthMeters * 0.15
    const n = offset * heightMeters * 0.1
    return {
      instant: tai(now + k * 1000),
      outcome: 'COMPUTED',
      footprintCorners: [[-1000, -1000], [1000, -1000], [1000, 1000], [-1000, 1000]].map(([de, dn]) => ({ eastMeters: e + de, northMeters: n + dn })),
      coverageFraction: 0.1,
    }
  })
  const scope = 'SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE'
  return {
    manifest: { id: evaluationId, query: { area }, aoiMapping, scope },
    result: { id: evaluationId, aoiMapping, samples, scope },
  }
}

// ---- workflow evidence derived from a request id ----

function requestFor(id: string, now: number) {
  const r = db.requests.find((x) => x.id === id) ?? fail(404, 'request not found')
  return { r, state: view(r, now) }
}
const ids = (reqId: string, rev: number) => ({
  attempt: `${reqId}~attempt-${rev}`,
  run: `${reqId}~run-${rev}`,
  image: `${reqId}~load-image`,
  downlink: `${reqId}~load-downlink`,
  product: `${reqId}~product`,
  scenario: `${reqId}~scenario`,
})
const owner = (derived: string) => derived.split('~')[0]

// Candidates are the first three propagated passes over the AOI centre within 7 days of planning;
// a pass is rejected when the sun is too low to image. Cached because the scan is not free.
const candidateCache = new Map<string, ReturnType<typeof computeCandidates>>()
function candidates(r: DemoRequest) {
  // Request ids repeat after a reset, so the stage start (new on create and revise) is part of the key.
  const key = `${r.id}:${r.stageStartMs}`
  const cached = candidateCache.get(key) ?? computeCandidates(r)
  candidateCache.set(key, cached)
  return cached
}
function computeCandidates(r: DemoRequest) {
  if (!r.area) return []
  const site = centre(r.area)
  const from = r.stageStartMs + STAGE_MS.RECEIVED
  return findAccessWindows(SATREC, site, from, from + 7 * 86_400_000, IMAGING).slice(0, 3).map((w, i) => {
    const sunDegrees = sunElevationDegrees(site, w.bestMs)
    const feasible = sunDegrees >= MINIMUM_SUN_ELEVATION_DEGREES
    const sun = Math.round(sunDegrees * 10) / 10
    return {
      id: { value: `${r.id}~cand-${i + 1}` },
      activity: { window: { start: tai(w.startMs), end: tai(w.endMs) }, definitionId: { value: 'IMAGE_CAPTURE' }, definitionVersion: 3 },
      feasibility: {
        feasible,
        issues: feasible ? [] : ['SUN_BELOW_MINIMUM_ELEVATION'],
        sunElevationDegrees: sun,
        offNadirDegrees: Math.round(w.bestOffNadirDegrees * 10) / 10,
      },
      phase: feasible ? 'PROPOSED' : 'REJECTED',
      mode: 'SIMULATION',
    }
  })
}

function commandEvidence(reqId: string, kind: 'image' | 'downlink', part: string, now: number) {
  const { r, state } = requestFor(reqId, now)
  if (state.body.request.status !== 'FULFILLED') fail(404, 'no released load')
  const rev = r.submissions.length
  const id = ids(reqId, rev)
  const operation = kind === 'image' ? 'IMAGE_CAPTURE' : 'DOWNLINK'
  const effect = kind === 'image' ? { generatedMegabytes: 1 } : { actualDrainMegabytes: 1 }
  if (part === 'simulation-release') {
    return {
      id: id[kind],
      body: {
        environment: 'SIMULATION',
        status: 'RELEASED',
        reviewer: 'operator1',
        reviewReference: 'DEMO 검토 기록',
        scenarioId: id.scenario,
        checks: { safety: { clear: true }, authority: { clear: true } },
        prepared: {
          load: { scheduleVersion: 1, scheduleKey: { spacecraftId: { value: CRAFT } } },
          sources: { schedule: { assignments: [{ requestId: { value: reqId }, runId: { value: id.run }, candidateId: { value: candidates(r).find((c) => c.feasibility.feasible)?.id.value ?? 'none' } }] } },
        },
      },
    }
  }
  if (part === 'simulation-delivery') {
    return { id: id[kind], body: { environment: 'SIMULATION', status: 'ACCEPTED', ledger: { body: { entries: [{ catalog: { template: { operation } }, status: 'PENDING' }] } } } }
  }
  return {
    id: id[kind],
    body: { environment: 'SIMULATION', status: 'SIMULATION_EFFECTS_CONFIRMED', ledger: { body: { entries: [{ catalog: { template: { operation } }, status: 'EXECUTED', effect }] } } },
  }
}

// ---- router ----

type Handler = (m: string[], ctx: { body: unknown; url: URL; headers: Headers; now: number }) => unknown
const routes: Array<[string, string, RegExp, Handler]> = [
  // Reference Data
  ['reference', 'GET', /^\/api\/places$/, (_, { url }) => placesFor(url.searchParams.get('target') ?? '')],
  ['reference', 'GET', /^\/api\/tracked-satellites$/, () => [
    { norad_id: NORAD_ID, display_name: 'SPACEEYE-T1', enabled: true, next_attempt_at: null, last_snapshot_id: ORBIT.id, last_error: null, fetched_at: new Date().toISOString() },
  ]],
  ['reference', 'GET', /^\/api\/tracked-satellites\/63229\/orbit$/, () => ORBIT],
  ['reference', 'POST', /^\/api\/tracked-satellites\/63229\/refresh$/, () => ({ status: 'QUEUED', note: 'DEMO — 외부 수집 없음' })],

  // Flight Dynamics
  ['flight', 'GET', /^\/api\/public-orbits\/([^/]+)$/, (m) => (m[1] === ORBIT.id && db.imported ? ORBIT : fail(404, 'not imported'))],
  ['flight', 'POST', /^\/api\/public-orbits\/import$/, () => {
    db.imported = true
    return { id: ORBIT.id, status: 'IMPORTED' }
  }],
  // Demo conversion uses a fixed TAI-UTC of 37 s (no leap-second table); labelled DEMO in the archive field.
  ['flight', 'POST', /^\/api\/time\/utc-to-tai$/, (_, { body }) => ({
    conversions: (body as { utc: string[] }).utc.map((utc) => ({ tai: tai(Date.parse(utc + 'Z')), utc })),
    referenceArchiveSha256: 'DEMO-fixed-37s',
  })],
  ['flight', 'POST', /^\/api\/time\/tai-to-utc$/, (_, { body }) => ({
    conversions: (body as { instants: MissionInstant[] }).instants.map((i) => ({ tai: i, utc: new Date(taiMs(i)).toISOString().replace('Z', '') })),
    referenceArchiveSha256: 'DEMO-fixed-37s',
  })],
  ['flight', 'POST', /^\/api\/public-orbits\/([^/]+)\/predictions$/, (m, { body }) => {
    const { horizon, stepSeconds } = body as { horizon: { start: MissionInstant; end: MissionInstant }; stepSeconds: number }
    const span = horizon.end.seconds - horizon.start.seconds
    if (stepSeconds < 1 || stepSeconds > 3600 || span > 7 * 86_400 || Math.ceil(span / stepSeconds) > 20_000)
      fail(400, 'Prediction must fit seven days and 20000 samples with step 1..3600 seconds')
    requireEpochValidity(horizon, 'Public GP epoch is more than seven days from prediction; refresh reference')
    const id = `demo-prediction-${++db.seq}`
    db.predictions.set(id, { ...horizon, stepSeconds })
    const sampleCount = Math.floor((horizon.end.seconds - horizon.start.seconds) / stepSeconds) + 1
    return { id, body: { id, solutionId: m[1], spacecraftId: 'SPACEEYE-T1', model: MODEL, frame: 'TEME', coverage: horizon, stepSeconds, sampleCount, referenceDigest: 'demo', objectReference: `demo://${id}` } }
  }],
  ['flight', 'GET', /^\/api\/predictions\/([^/]+)\/samples$/, (m): EphemerisDocument => {
    const p = db.predictions.get(m[1]) ?? fail(404, 'prediction not found')
    const count = Math.floor((p.end.seconds - p.start.seconds) / p.stepSeconds) + 1
    return {
      prediction: { solutionId: ORBIT.id, model: MODEL, frame: 'TEME', coverage: { start: p.start, end: p.end }, stepSeconds: p.stepSeconds, samples: [] },
      groundTrack: groundTrack(p.start, count, p.stepSeconds),
      referenceDigest: 'demo',
    }
  }],
  ['flight', 'POST', /^\/api\/public-orbits\/([^/]+)\/access-predictions$/, (m, { body }) => {
    const query = body as AccessQuery
    if (query.kind !== 'POINT_IMAGING') fail(400, 'demo supports POINT_IMAGING only')
    if (query.horizon.end.seconds - query.horizon.start.seconds > 86_400) fail(400, 'Access search is limited to 24 hours per request')
    requireEpochValidity(query.horizon, 'Initial orbit is outside model validity interval')
    const windows = findAccessWindows(SATREC, query.target, taiMs(query.horizon.start), taiMs(query.horizon.end), query)
      .map((w) => ({ start: tai(w.startMs), end: tai(w.endMs) }))
    return { id: `demo-access-${++db.seq}`, body: { solutionId: m[1], spacecraftId: 'SPACEEYE-T1', model: MODEL, referenceDigest: 'demo', query, rootToleranceSeconds: 1, maximumCheckSeconds: 10, windows } }
  }],

  // Tasking
  ['tasking', 'POST', /^\/api\/requests$/, (_, { body, now }) => {
    const submission = body as Submission
    const id = `demo-req-${String(db.requests.length + 1).padStart(4, '0')}`
    db.requests.push({ id, submissions: [submission], ...resolveArea(submission), createdMs: now, stageStartMs: now, baseVersion: 1, cancelled: null })
    return { id, version: 1 }
  }],
  ['tasking', 'GET', /^\/api\/requests$/, (_, { now }) => ({ items: [...db.requests].reverse().map((r) => view(r, now)), nextCursor: null })],
  ['tasking', 'GET', /^\/api\/requests\/([^/]+)$/, (m, { now }) => requestFor(m[1], now).state],
  ['tasking', 'GET', /^\/api\/requests\/([^/]+)\/submissions\/(\d+)$/, (m, { now }) => requestFor(m[1], now).r.submissions[Number(m[2]) - 1] ?? fail(404, 'no such revision')],
  ['tasking', 'PUT', /^\/api\/requests\/([^/]+)$/, (m, { body, now }) => {
    const { r, state } = requestFor(m[1], now)
    const { expectedVersion, submission } = body as { expectedVersion: number; submission: Submission }
    if (expectedVersion !== state.version || /^(FULFILLED|CANCELLED)$/.test(state.body.request.status)) fail(409, 'version conflict')
    r.submissions.push(submission)
    Object.assign(r, resolveArea(submission), { stageStartMs: now, baseVersion: state.version + 1 })
    return { id: r.id, version: r.baseVersion }
  }],
  ['tasking', 'POST', /^\/api\/requests\/([^/]+)\/cancel$/, (m, { body, now }) => {
    const { r, state } = requestFor(m[1], now)
    const status = state.body.request.status
    if ((body as { expectedVersion: number }).expectedVersion !== state.version || /^(FULFILLED|CANCELLED)$/.test(status)) fail(409, 'version conflict')
    r.cancelled = { version: state.version + 1, atMs: now, planned: status === 'ACCEPTED' || status === 'SCHEDULED' }
    return { id: r.id, version: r.cancelled.version }
  }],
  ['tasking', 'GET', /^\/api\/requests\/([^/]+)\/simulation-result$/, (m, { now }) => {
    const { r, state } = requestFor(m[1], now)
    if (state.body.request.status !== 'FULFILLED') fail(404, 'no simulation result')
    const id = ids(r.id, r.submissions.length)
    return {
      id: id.product,
      version: 1,
      body: {
        requestId: r.id,
        requestRevision: r.submissions.length,
        environment: 'SIMULATION',
        status: 'COMPLETE',
        completionBasis: 'V1_FUNCTIONAL_WORKFLOW_PHYSICAL_IMAGE_QUALITY_NOT_ASSESSED',
        productId: id.product,
        scenarioId: id.scenario,
        imageLoadId: id.image,
        downlinkLoadId: id.downlink,
        reviewer: 'operator1',
        reviewReference: 'DEMO 검토 기록',
        completedAt: state.body.updatedAt,
        sourceHashes: { imageRelease: sha(id.image + 'r'), imageExecution: sha(id.image + 'e'), downlinkRelease: sha(id.downlink + 'r'), downlinkExecution: sha(id.downlink + 'e'), product: sha(id.product) },
        sources: [{ receiptId: `${r.id}~receipt-1`, payloadId: `${r.id}~payload-1`, byteCount: 1_000_000, sha256: sha(r.id + 'payload'), previewByteCount: 651, previewSha256: sha(r.id + 'png'), contentPath: 'demo', previewPath: 'demo' }],
      },
    }
  }],

  // Planning
  ['planning', 'GET', /^\/api\/planning\/requests\/([^/]+)$/, (m, { now }) => {
    const { r, state } = requestFor(m[1], now)
    const status = state.body.request.status
    // Only requests that reached ACCEPTED ever entered the planning queue.
    if (status === 'RECEIVED' || status === 'CLARIFICATION_NEEDED' || (r.cancelled && !r.cancelled.planned)) fail(404, 'not queued')
    const rev = r.submissions.length
    return {
      request_id: r.id,
      revision: rev,
      status: status === 'CANCELLED' ? 'INVALIDATED' : status === 'ACCEPTED' ? 'EVALUATING' : 'SIMULATION_COMMITTED',
      attempts: 1,
      last_attempt_id: ids(r.id, rev).attempt,
      next_attempt_at: null,
    }
  }],
  ['planning', 'GET', /^\/api\/planning\/input-attempts\/([^/]+)$/, (m, { now }) => {
    const { r } = requestFor(owner(m[1]), now)
    const inputs = Object.fromEntries(['orbit', 'monitoring', 'safety', 'missionDefinition'].map((k) => [k, { service: k, path: `demo://${k}`, sha256: sha(k), value: null }]))
    return { id: m[1], requestId: r.id, revision: r.submissions.length, capturedAt: tai(r.stageStartMs + STAGE_MS.RECEIVED), status: 'TERMINAL', request: null, assets: [{ spacecraftId: CRAFT, inputs, missing: [] }], issues: [] }
  }],
  ['planning', 'GET', /^\/api\/planning\/input-attempts\/([^/]+)\/runs$/, (m, { now }) => {
    const { r } = requestFor(owner(m[1]), now)
    return { runIds: [ids(r.id, r.submissions.length).run] }
  }],
  ['planning', 'GET', /^\/api\/planning\/runs\/([^/]+)$/, (m, { now }) => {
    const { r } = requestFor(owner(m[1]), now)
    return { id: m[1], requestId: r.id, requestRevision: r.submissions.length, spacecraftId: CRAFT, sourceHashes: { orbit: sha('orbit') }, run: { startedAt: tai(r.stageStartMs + STAGE_MS.RECEIVED), candidates: candidates(r), opportunities: [], decisions: [] } }
  }],
  ['planning', 'GET', /^\/api\/planning\/runs\/([^/]+)\/resources$/, (m, { now }) => ({
    id: m[1],
    body: {
      scope: 'MODEL_BASED_PREDICTION_NOT_RESERVATION',
      supplyAssumption: 'DEMO 태양광 공급 가정',
      propellantInterpretation: '촬영 활동은 추진제를 쓰지 않음',
      scheduleContextStatus: 'NO_COMMITTED_SCHEDULE_CONFLICT',
      candidates: candidates(requestFor(owner(m[1]), now).r).map((c) => ({ candidateId: c.id.value, issues: c.feasibility.feasible ? [] : ['ILLUMINATION_REJECTED'] })),
    },
  })],
  ['planning', 'GET', /^\/api\/planning\/runs\/([^/]+)\/camera-work$/, (m) => ({ run_id: m[1], status: 'EVALUATED', camera_model_version: 1, attempts: 1, last_assessment_id: sha(m[1]).slice(0, 24), last_issue: null })],
  ['planning', 'GET', /^\/api\/planning\/runs\/([^/]+)\/illumination-work$/, (m) => ({ run_id: m[1], status: 'EVALUATED', attempts: 1, last_issue: null })],
  ['planning', 'GET', /^\/api\/planning\/runs\/([^/]+)\/camera\/(\d+)$/, (m, { now }) => ({
    id: m[1],
    body: {
      scope: 'SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE',
      // Only sun-feasible propagated passes get a camera (footprint) evaluation.
      candidates: candidates(requestFor(owner(m[1]), now).r)
        .filter((c) => c.feasibility.feasible)
        .map((c) => {
          const fp = c.id.value.replace('~cand-', '~fp-')
          return { candidateId: c.id.value, cameraResult: { id: fp, body: { id: fp, scope: 'SAMPLED_FOOTPRINT_NOT_CONTINUOUS_EXPOSURE', sampleCount: 10 } } }
        }),
    },
  })],

  // Flight Dynamics sampled footprints (demo: synthetic squares inside the request AOI)
  ['flight', 'GET', /^\/api\/camera-footprint-evaluations\/([^/]+)$/, (m, { now }) => demoFootprint(m[1], now).manifest],
  ['flight', 'GET', /^\/api\/camera-footprint-evaluations\/([^/]+)\/result$/, (m, { now }) => demoFootprint(m[1], now).result],

  // Spacecraft Control
  ['control', 'GET', /^\/api\/command-loads\/([^/]+)\/(simulation-release|simulation-delivery|simulation-execution)$/, (m, { now }) => {
    const kind = m[1].endsWith('~load-image') ? 'image' : m[1].endsWith('~load-downlink') ? 'downlink' : fail(404, 'unknown load')
    return commandEvidence(owner(m[1]), kind, m[2], now)
  }],

  // Product
  ['product', 'GET', /^\/api\/products\/simulation-source-packages\/([^/]+)$/, (m) => {
    const reqId = owner(m[1])
    return {
      id: m[1],
      body: {
        environment: 'SIMULATION',
        format: 'SIMULATION_SOURCE_V1',
        byteCount: 1_000_000,
        acquisitionManifest: {
          body: {
            completeness: 'COMPLETE',
            expectedBytes: 1_000_000,
            receivedBytes: 1_000_000,
            expected: [{ planId: `${reqId}~downlink-plan`, plan: { body: { booking: { id: `${reqId}~booking`, status: 'CONFIRMED', request: { stationId: 'sim-daejeon-gs', requestedMegabytes: 1 } } } } }],
          },
        },
      },
    }
  }],

  // Monitoring
  ['monitoring', 'GET', /^\/api\/spacecraft-estimates\/([^/]+)$/, (m, { now }) => {
    if (m[1] !== CRAFT) fail(404, 'no telemetry binding')
    const frame = { id: 'demo-frame-42', spacecraftId: CRAFT, bindingVersion: 1, source: 'simulator', sequence: 42, observedAt: tai(now - 20_000), quality: 'GOOD', mode: 'NOMINAL', batteryWh: 72.5, storageMb: 380, propellantKg: 4.82, provenance: 'simulator://demo/frame/42' }
    const receipt = { frame, receivedAt: tai(now - 18_000), disposition: 'ACCEPTED' }
    return {
      estimate: { id: CRAFT, version: 42, body: { binding: { spacecraftId: CRAFT, version: 1, source: 'simulator', environment: 'SIMULATION', maximumAgeSeconds: 300, futureSkewSeconds: 5, approvalReference: 'DEMO' }, accepted: receipt, newestEvidence: receipt } },
      evaluatedAt: tai(now),
      confidence: 'FRESH',
    }
  }],

  // Anomaly
  ['anomaly', 'GET', /^\/api\/safety\/([^/]+)$/, (m) => (m[1] === CRAFT ? db.safety.history[db.safety.history.length - 1] : fail(404, 'no safety policy'))],
  ['anomaly', 'GET', /^\/api\/safety-policies\/([^/]+)$/, (m) => (m[1] === CRAFT
    ? { id: CRAFT, version: 1, body: { spacecraftId: CRAFT, version: 1, telemetryBindingVersion: 1, minimumBatteryWh: 20, maximumStorageMb: 500, minimumPropellantKg: 1, recoveryApprovals: 2, approvalValiditySeconds: 3600, approvalReference: 'DEMO 합성 안전 정책' } }
    : fail(404, 'no safety policy'))],
  ['anomaly', 'GET', /^\/api\/safety\/([^/]+)\/history$/, (m) => (m[1] === CRAFT ? db.safety.history : fail(404, 'no safety policy'))],
  ['anomaly', 'POST', /^\/api\/safety\/([^/]+)\/check$/, (m, { now }) => {
    if (m[1] !== CRAFT) fail(404, 'no safety policy')
    const latest = db.safety.history[db.safety.history.length - 1]
    return { clear: true, safetyVersion: latest.version, policyVersion: 1, monitoringVersion: 42, evaluatedAt: tai(now), currentReasons: [], latch: latest.body }
  }],
  ['anomaly', 'GET', /^\/api\/anomalies$/, (_, { now }) => [
    { id: 'demo-anomaly-1', version: 1, body: { anomaly: { id: { value: 'demo-anomaly-1' }, spacecraftId: { value: CRAFT }, severity: 'CRITICAL', declaredAt: tai(now - 86_400_000), evidenceReference: 'demo' }, policyVersion: 1, generation: 1, reasons: ['OPERATOR_ENABLE_REQUIRED'] } },
  ]],
  // Two distinct operators must approve before the latch releases (recoveryApprovals = 2).
  ['anomaly', 'POST', /^\/api\/safety\/([^/]+)\/recovery-approvals$/, (m, { body, headers, now }) => {
    if (m[1] !== CRAFT) fail(404, 'no safety policy')
    const { expectedSafetyVersion, decisionReference } = body as { expectedSafetyVersion: number; decisionReference: string }
    const latest = db.safety.history[db.safety.history.length - 1]
    if (expectedSafetyVersion !== latest.version) fail(409, 'safety version changed')
    const actor = headers.get('X-Msc-Actor') ?? 'operator1'
    if (!latest.body.frozen || latest.body.approvals.some((a) => a.actor === actor)) return { approved: true, state: latest }
    const approvals = [...latest.body.approvals, { actor, decisionReference, grantedAt: tai(now), validUntil: tai(now + 3_600_000), generation: latest.body.generation }]
    const released = approvals.length >= 2
    const next: SafetyLatchState = { id: CRAFT, version: latest.version + 1, body: { ...latest.body, frozen: !released, reasons: released ? [] : latest.body.reasons, approvals: released ? [] : approvals } }
    db.safety.history.push(next)
    return { approved: true, state: next }
  }],
]

const DELAY_MS = 150

export async function demoFetch(path: string, init?: RequestInit): Promise<Response> {
  await new Promise((done) => setTimeout(done, DELAY_MS))
  const url = new URL(path, 'http://demo.local')
  const prefix = url.pathname.match(/^\/fe-api\/([^/]+)(\/.*)$/)
  const method = (init?.method ?? 'GET').toUpperCase()
  let status = 404
  let payload: unknown = { message: 'DEMO: 이 경로는 데모 백엔드에 없습니다.' }
  if (prefix) {
    const [, service, rest] = prefix
    for (const [routeService, routeMethod, pattern, handler] of routes) {
      const m = routeService === service && routeMethod === method ? rest.match(pattern) : null
      if (!m) continue
      try {
        const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
        payload = handler(m.map((part) => decodeURIComponent(part)), { body, url, headers: new Headers(init?.headers), now: Date.now() })
        status = 200
      } catch (error) {
        if (!(error instanceof HttpError)) throw error
        status = error.status
        payload = { message: `DEMO: ${error.message}` }
      }
      break
    }
    calls = [{ seq: ++db.seq, service: SERVICE_NAME[service] ?? service, method, path: rest.split('?')[0], status }, ...calls].slice(0, 40)
    listeners.forEach((l) => l(calls))
  }
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } })
}
