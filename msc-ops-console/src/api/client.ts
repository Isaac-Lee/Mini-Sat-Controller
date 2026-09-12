export type ApiErrorKind = 'auth' | 'not_found' | 'conflict' | 'connection' | 'invalid' | 'unknown'

export class ApiError extends Error {
  kind: ApiErrorKind
  status?: number
  constructor(kind: ApiErrorKind, message: string, status?: number) {
    super(message)
    this.kind = kind
    this.status = status
  }
}

function kindForStatus(status: number): ApiErrorKind {
  if (status === 401 || status === 403) return 'auth'
  if (status === 404) return 'not_found'
  if (status === 409) return 'conflict'
  if (status === 400) return 'invalid'
  if (status >= 500) return 'connection'
  return 'unknown'
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw new ApiError('connection', '개발 프록시 또는 백엔드 서비스에 연결할 수 없습니다.')
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      /* ignore non-JSON error body */
    }
    throw new ApiError(kindForStatus(res.status), message, res.status)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

const REF = '/fe-api/reference'
const FLIGHT = '/fe-api/flight'

export const api = {
  trackedSatellites: () => request<import('./types').TrackedSatelliteRow[]>(`${REF}/api/tracked-satellites`),

  latestOrbit: (noradId: number) =>
    request<import('./types').OrbitSnapshot>(`${REF}/api/tracked-satellites/${noradId}/orbit`),

  requestCollection: (noradId: number) =>
    request<Record<string, unknown>>(`${REF}/api/tracked-satellites/${noradId}/refresh`, {
      method: 'POST',
    }),

  publicOrbit: (id: string) => request<import('./types').OrbitSnapshot>(`${FLIGHT}/api/public-orbits/${id}`),

  importPublicOrbit: (snapshotId: string, idempotencyKey: string) =>
    request<Record<string, unknown>>(`${FLIGHT}/api/public-orbits/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ snapshotId }),
    }),

  utcToTai: (utc: string) =>
    request<import('./types').MissionInstant>(`${FLIGHT}/internal/time/utc-to-tai`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ utc }),
    }),

  predict: (
    snapshotId: string,
    horizon: { start: import('./types').MissionInstant; end: import('./types').MissionInstant },
    stepSeconds: number,
    idempotencyKey: string,
  ) =>
    request<{ id: string; body: import('./types').PredictionManifest }>(
      `${FLIGHT}/api/public-orbits/${snapshotId}/predictions`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ horizon, stepSeconds }),
      },
    ),

  samples: (predictionId: string) =>
    request<import('./types').EphemerisDocument>(`${FLIGHT}/api/predictions/${predictionId}/samples`),

  requestAccessPrediction: (
    publicOrbitId: string,
    query: import('./types').AccessQuery,
    idempotencyKey: string,
  ) =>
    request<{ id: string; body: import('./types').AccessPrediction }>(
      `${FLIGHT}/api/public-orbits/${publicOrbitId}/access-predictions`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(query),
      },
    ),

  accessPrediction: (id: string) =>
    request<import('./types').AccessPrediction>(`${FLIGHT}/api/access-predictions/${id}`),
}
