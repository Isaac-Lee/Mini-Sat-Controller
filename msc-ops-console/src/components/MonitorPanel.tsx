import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { monitoringApi } from '../api/monitoring'
import type { EstimateView, Confidence } from '../api/types'
import { TimeValue } from './TimeValue'
import type { TaiUtcOffset } from '../time'

const CONFIDENCE_LABEL: Record<Confidence, string> = {
  UNKNOWN: 'UNKNOWN — 사용 가능한 증거 없음',
  FRESH: 'FRESH — 최신 정상 증거',
  DEGRADED: 'DEGRADED — 더 최신의 불량/미사용 증거 있음',
  STALE: 'STALE — 최대 허용 기간 경과',
}

const CONFIDENCE_CLASS: Record<Confidence, string> = {
  UNKNOWN: 'status-disabled',
  FRESH: 'status-ok',
  DEGRADED: 'status-warn',
  STALE: 'status-error',
}

interface Props {
  initialSpacecraftId?: string
  demoMode: boolean
  offset: TaiUtcOffset | null
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `[${error.kind}] ${error.message}`
  return String(error)
}

export function MonitorPanel({ demoMode, offset, initialSpacecraftId = 'sim-spaceeye-t1' }: Props) {
  const [spacecraftId, setSpacecraftId] = useState(initialSpacecraftId)
  const [phase, setPhase] = useState<'idle' | 'loading' | 'ready' | 'not_found' | 'error'>('idle')
  const [errorText, setErrorText] = useState('')
  const [view, setView] = useState<EstimateView | null>(null)

  async function load() {
    if (demoMode) return
    setPhase('loading')
    setErrorText('')
    try {
      const result = await monitoringApi.currentEstimate(spacecraftId)
      setView(result)
      setPhase('ready')
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'not_found') {
        setPhase('not_found')
      } else {
        setPhase('error')
        setErrorText(errorMessage(error))
      }
      setView(null)
    }
  }

  useEffect(() => {
    if (!demoMode) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode])

  if (demoMode) {
    return (
      <section className="panel-block">
        <h2>모니터링</h2>
        <div className="demo-banner">데모 모드 — 아래는 예시 데이터이며 실제 관측이 아닙니다.</div>
        <dl>
          <dt>Confidence</dt>
          <dd className="status-ok">FRESH (데모)</dd>
          <dt>Mode</dt>
          <dd>NOMINAL (데모)</dd>
        </dl>
      </section>
    )
  }

  const estimate = view?.estimate.body
  const accepted = estimate?.accepted
  const newest = estimate?.newestEvidence

  return (
    <section className="panel-block">
      <h2>모니터링</h2>
      <label htmlFor="mon-craft">위성 ID (관측 바인딩 등록된 식별자)</label>
      <div className="inline-form">
        <input id="mon-craft" value={spacecraftId} onChange={(e) => setSpacecraftId(e.target.value)} />
        <button onClick={load} disabled={phase === 'loading'} aria-busy={phase === 'loading'}>
          조회
        </button>
      </div>

      {phase === 'loading' && <div className="hint">불러오는 중...</div>}
      {phase === 'not_found' && (
        <div className="status-warn" role="alert">
          관측 바인딩 미등록: 이 위성 ID에 등록된 텔레메트리 바인딩이 없습니다(ADMIN이
          telemetry-bindings를 먼저 등록해야 함).
        </div>
      )}
      {phase === 'error' && (
        <div className="status-error" role="alert">
          조회 실패: {errorText}
        </div>
      )}

      {phase === 'ready' && view && estimate && (
        <>
          <dl>
            <dt>Confidence</dt>
            <dd className={CONFIDENCE_CLASS[view.confidence]}>{CONFIDENCE_LABEL[view.confidence]}</dd>
            <dt>평가 시각</dt>
            <dd>
              <TimeValue instant={view.evaluatedAt} offset={offset} />
            </dd>
            <dt>바인딩 환경</dt>
            <dd className={estimate.binding.environment === 'HARDWARE' ? 'status-warn' : ''}>
              {estimate.binding.environment}
              {estimate.binding.environment === 'SIMULATION' && ' (시뮬레이션 소스, 실제 하드웨어 아님)'}
            </dd>
            <dt>바인딩 소스 / 버전</dt>
            <dd className="mono">
              {estimate.binding.source} v{estimate.binding.version}
            </dd>
            <dt>최대 허용 지연(초)</dt>
            <dd>{estimate.binding.maximumAgeSeconds}</dd>
          </dl>

          {accepted ? (
            <dl>
              <dt>Mode</dt>
              <dd className={accepted.frame.mode === 'SAFE' ? 'status-error' : ''}>{accepted.frame.mode}</dd>
              <dt>배터리 (Wh)</dt>
              <dd>{accepted.frame.batteryWh}</dd>
              <dt>저장공간 (MB)</dt>
              <dd>{accepted.frame.storageMb}</dd>
              <dt>추진제 (kg)</dt>
              <dd>{accepted.frame.propellantKg}</dd>
              <dt>관측 시각 (observedAt)</dt>
              <dd>
                <TimeValue instant={accepted.frame.observedAt} offset={offset} />
              </dd>
              <dt>수신 시각 (receivedAt)</dt>
              <dd>
                <TimeValue instant={accepted.receivedAt} offset={offset} />
              </dd>
              <dt>출처 (provenance)</dt>
              <dd className="mono">{accepted.frame.provenance}</dd>
            </dl>
          ) : (
            <div className="status-warn">채택된(accepted) 증거 없음</div>
          )}

          {newest && accepted && newest.frame.id !== accepted.frame.id && (
            <div className="status-warn" role="alert">
              더 최신의 미채택 증거 있음 (disposition: {newest.disposition}) — 현재 accepted 값이 최신이
              아닐 수 있습니다.
            </div>
          )}
        </>
      )}
    </section>
  )
}
