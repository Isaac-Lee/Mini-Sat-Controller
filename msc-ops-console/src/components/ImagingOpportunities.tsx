import { useEffect, useState } from 'react'
import { api, newIdempotencyKey } from '../api/client'
import type { AccessPrediction, Area, OrbitSnapshot, TimeWindow } from '../api/types'
import { apiProblem } from '../api/workflow'
import type { TaiUtcOffset } from '../time'
import { TimeValue } from './TimeValue'

// Same point-imaging query as the timeline tab, over a week: a ~500 km sun-synchronous satellite
// with a 30° off-nadir limit revisits a mid-latitude point only every few days.
const HORIZON_DAYS = 7
const CRITERIA = { minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 }

interface Props {
  area: Area
  flightOrbit: OrbitSnapshot | 'missing' | 'unknown'
  onImportOrbit: () => void
  importBusy: boolean
  onShowPass: (window: TimeWindow) => void
  offset: TaiUtcOffset | null
}

const utcNoZ = (ms: number) => new Date(ms).toISOString().replace(/\.\d+Z$/, '')

// Computed automatically when a request is shown. The key and horizon are fixed per mount, so a
// repeated effect run (React StrictMode) replays the same Flight Dynamics request instead of
// storing a second prediction.
export function ImagingOpportunities({ area, flightOrbit, onImportOrbit, importBusy, onShowPass, offset }: Props) {
  const [query] = useState(() => ({ key: newIdempotencyKey(), fromMs: Date.now() }))
  const [state, setState] = useState<{ prediction?: AccessPrediction; error?: string }>({})
  const orbitId = typeof flightOrbit === 'string' ? null : flightOrbit.id

  useEffect(() => {
    if (!orbitId) return
    let active = true
    setState({})
    ;(async () => {
      const start = await api.utcToTai(utcNoZ(query.fromMs))
      const end = await api.utcToTai(utcNoZ(query.fromMs + HORIZON_DAYS * 86_400_000))
      const target = {
        id: `${area.id}:center`,
        latitudeDegrees: (area.south + area.north) / 2,
        longitudeDegrees: (area.west + area.east) / 2,
        altitudeMeters: 0,
      }
      const result = await api.requestAccessPrediction(orbitId, { kind: 'POINT_IMAGING', target, horizon: { start, end }, ...CRITERIA }, query.key)
      if (active) setState({ prediction: result.body })
    })().catch((error) => {
      if (active) setState({ error: apiProblem(error) })
    })
    return () => {
      active = false
    }
    // The parent keys this component by request id and revision, so the area cannot change while
    // mounted; a list refresh only hands over an equal copy and must not recompute.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orbitId, query])

  return (
    <section className="imaging-opportunities" aria-label="예상 촬영 가능 시각">
      <h4>예상 촬영 가능 시각 (궤도 전파)</h4>
      <p className="hint">
        AOI 중심점({((area.south + area.north) / 2).toFixed(3)}°, {((area.west + area.east) / 2).toFixed(3)}°)이 고도각 ≥{' '}
        {CRITERIA.minimumElevationDegrees}°, off-nadir ≤ {CRITERIA.maximumOffNadirDegrees}°로 보이는 구간입니다. 향후 {HORIZON_DAYS}일. AOI 전체
        커버리지·조명·확정 일정이 아니며, 실제 촬영 여부는 계획(임무 흐름)에서 결정됩니다.
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
      {orbitId && !state.prediction && !state.error && <p role="status">궤도 전파로 계산 중...</p>}
      {state.error && <p className="status-error" role="alert">계산 실패: {state.error}</p>}
      {state.prediction && (
        <>
          <p className="hint">모델: {state.prediction.model}</p>
          {state.prediction.windows.length === 0 ? (
            <p className="status-warn">향후 {HORIZON_DAYS}일 안에 조건을 만족하는 통과가 없습니다.</p>
          ) : (
            <ol className="access-window-list">
              {state.prediction.windows.map((w) => (
                <li key={w.start.seconds}>
                  <TimeValue instant={w.start} offset={offset} /> 부터 {w.end.seconds - w.start.seconds}초{' '}
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
