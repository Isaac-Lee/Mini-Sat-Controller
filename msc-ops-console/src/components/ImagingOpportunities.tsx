import { useEffect, useState } from 'react'
import { api, newIdempotencyKey } from '../api/client'
import type { Area, OrbitSnapshot, TimeWindow } from '../api/types'
import { apiProblem } from '../api/workflow'
import { TimeValue } from './TimeValue'
import { epochUtcMs, epochValidityMs } from '../time'

// Same point-imaging query as the timeline tab, over up to a week: a ~500 km sun-synchronous
// satellite with a 30° off-nadir limit revisits a mid-latitude point only every few days.
const DAY_MS = 86_400_000
const HORIZON_MS = 7 * DAY_MS
// Flight Dynamics limits (OrekitAccessPredictor): one access search covers at most 24 h, and both
// ends must lie within ±7 days of the GP epoch. Longer horizons are split and clipped to fit.
const MAX_SEARCH_MS = DAY_MS
const CRITERIA = { minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 }
// ponytail: results are reused for an hour per orbit and AOI, so tab switches do not store a new
// Flight Dynamics prediction each time; past windows stay listed until the entry expires.
const CACHE_MS = 60 * 60_000

interface Props {
  area: Area
  flightOrbit: OrbitSnapshot | 'missing' | 'unknown'
  onImportOrbit: () => void
  importBusy: boolean
  onShowPass: (window: TimeWindow) => void
}

interface Opportunities {
  model: string
  windows: TimeWindow[]
  /** Last instant actually searched; earlier than now + 7 days when the GP epoch limits it. */
  searchedUntilMs: number
  clippedByEpoch: boolean
}

const utcNoZ = (ms: number) => new Date(ms).toISOString().replace(/\.\d+Z$/, '')

/** [start, end) UTC ms ranges of at most 24 h covering now..now+7d inside the epoch validity. */
export function searchRanges(fromMs: number, epochMs: number): Array<[number, number]> {
  const [validFrom, validTo] = epochValidityMs(epochMs)
  const start = Math.max(Math.ceil(fromMs / 1000) * 1000, validFrom)
  const end = Math.min(fromMs + HORIZON_MS, validTo)
  const ranges: Array<[number, number]> = []
  for (let s = start; s < end; s += MAX_SEARCH_MS) ranges.push([s, Math.min(s + MAX_SEARCH_MS, end)])
  return ranges
}

// A pass that straddles a range boundary comes back as two touching windows; join them.
function joinTouching(windows: TimeWindow[]): TimeWindow[] {
  const joined: TimeWindow[] = []
  for (const w of [...windows].sort((a, b) => a.start.seconds - b.start.seconds || a.start.nanos - b.start.nanos)) {
    const last = joined[joined.length - 1]
    if (last && last.end.seconds === w.start.seconds && last.end.nanos === w.start.nanos) joined[joined.length - 1] = { start: last.start, end: w.end }
    else joined.push(w)
  }
  return joined
}

async function search(orbit: OrbitSnapshot, area: Area): Promise<Opportunities> {
  const nowMs = Date.now()
  const epochMs = epochUtcMs(orbit.elements.epochUtc)
  const ranges = searchRanges(nowMs, epochMs)
  if (ranges.length === 0) return { model: '', windows: [], searchedUntilMs: nowMs, clippedByEpoch: true }
  const bounds = [ranges[0][0], ...ranges.map(([, end]) => end)]
  const tai = (await api.convertUtc(bounds.map(utcNoZ))).conversions.map((c) => c.tai)
  const target = {
    id: `${area.id}:center`,
    latitudeDegrees: (area.south + area.north) / 2,
    longitudeDegrees: (area.west + area.east) / 2,
    altitudeMeters: 0,
  }
  const results = await Promise.all(
    ranges.map((_, i) =>
      api.requestAccessPrediction(orbit.id, { kind: 'POINT_IMAGING', target, horizon: { start: tai[i], end: tai[i + 1] }, ...CRITERIA }, newIdempotencyKey()),
    ),
  )
  const searchedUntilMs = bounds[bounds.length - 1]
  return {
    model: results[0].body.model,
    windows: joinTouching(results.flatMap((r) => r.body.windows)),
    searchedUntilMs,
    clippedByEpoch: searchedUntilMs < nowMs + HORIZON_MS,
  }
}

// One shared promise per orbit and AOI: React StrictMode re-runs and remounts after a tab switch
// reuse it instead of sending the Flight Dynamics requests again.
const cache = new Map<string, { atMs: number; result: Promise<Opportunities> }>()
function cachedSearch(orbit: OrbitSnapshot, area: Area): Promise<Opportunities> {
  const key = `${orbit.id}:${area.west},${area.south},${area.east},${area.north}`
  const hit = cache.get(key)
  if (hit && Date.now() - hit.atMs < CACHE_MS) return hit.result
  const result = search(orbit, area)
  cache.set(key, { atMs: Date.now(), result })
  result.catch(() => cache.delete(key))
  return result
}

export function ImagingOpportunities({ area, flightOrbit, onImportOrbit, importBusy, onShowPass }: Props) {
  const [state, setState] = useState<{ data?: Opportunities; error?: string }>({})
  const orbit = typeof flightOrbit === 'string' ? null : flightOrbit

  useEffect(() => {
    if (!orbit) return
    let active = true
    setState({})
    cachedSearch(orbit, area).then(
      (data) => active && setState({ data }),
      (error) => active && setState({ error: apiProblem(error) }),
    )
    return () => {
      active = false
    }
    // The parent keys this component by request id and revision, so the area cannot change while
    // mounted; a list refresh only hands over an equal copy and must not recompute.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orbit?.id])

  const data = state.data
  return (
    <section className="imaging-opportunities" aria-label="예상 촬영 가능 시각">
      <h4>예상 촬영 가능 시각 (궤도 전파)</h4>
      <p className="hint">
        AOI 중심점({((area.south + area.north) / 2).toFixed(3)}°, {((area.west + area.east) / 2).toFixed(3)}°)이 고도각 ≥{' '}
        {CRITERIA.minimumElevationDegrees}°, off-nadir ≤ {CRITERIA.maximumOffNadirDegrees}°로 보이는 구간입니다. 최대 7일, 24시간 단위로
        나눠 계산합니다. AOI 전체 커버리지·조명·확정 일정이 아니며, 실제 촬영 여부는 계획(임무 흐름)에서 결정됩니다.
      </p>
      {flightOrbit === 'unknown' && <p className="hint">궤도 상태 확인 중...</p>}
      {flightOrbit === 'missing' && (
        <p className="status-warn">
          Flight Dynamics에 이 위성의 공개 궤도가 아직 없습니다.{' '}
          <button onClick={onImportOrbit} disabled={importBusy} aria-busy={importBusy}>
            궤도 가져오기
          </button>
        </p>
      )}
      {orbit && !data && !state.error && <p role="status">궤도 전파로 계산 중...</p>}
      {state.error && <p className="status-error" role="alert">계산 실패: {state.error}</p>}
      {data && (
        <>
          {data.model && <p className="hint">모델: {data.model} · 계산 범위 끝: {new Date(data.searchedUntilMs).toISOString().replace('.000Z', 'Z')}</p>}
          {data.clippedByEpoch && (
            <p className="status-warn">
              궤도 요소 epoch({orbit?.elements.epochUtc} UTC)로부터 7일이 모델 유효 범위라 그 이후는 계산하지 않았습니다. 더 먼 시각은 궤도를
              새로 수집한 뒤 확인하세요.
            </p>
          )}
          {data.windows.length === 0 ? (
            <p className="status-warn">계산 범위 안에 조건을 만족하는 통과가 없습니다.</p>
          ) : (
            <ol className="access-window-list">
              {data.windows.map((w) => (
                <li key={`${w.start.seconds}.${w.start.nanos}`}>
                  <TimeValue instant={w.start} /> 부터 {w.end.seconds - w.start.seconds}초{' '}
                  <button onClick={() => onShowPass(w)}>지도에서 보기</button>
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </section>
  )
}
