import { useEffect, useRef, useState } from 'react'
import { ApiError, newIdempotencyKey } from '../api/client'
import { anomalyApi, type OperatorActor } from '../api/anomaly'
import type { AnomalyState, SafetyCheck, SafetyLatchState } from '../api/types'
import { TimeValue } from './TimeValue'
import type { TaiUtcOffset } from '../time'

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `[${error.kind}] ${error.message}`
  return String(error)
}

interface Props {
  initialSpacecraftId?: string
  demoMode: boolean
  offset: TaiUtcOffset | null
}

export function AuthorityPanel({ demoMode, offset, initialSpacecraftId = 'sim-spaceeye-t1' }: Props) {
  const [spacecraftId, setSpacecraftId] = useState(initialSpacecraftId)
  const [phase, setPhase] = useState<'idle' | 'loading' | 'ready' | 'not_found' | 'error'>('idle')
  const [errorText, setErrorText] = useState('')
  const [latch, setLatch] = useState<SafetyLatchState | null>(null)

  const [checkBusy, setCheckBusy] = useState(false)
  const [checkResult, setCheckResult] = useState<SafetyCheck | null>(null)
  const [checkError, setCheckError] = useState('')

  const [actor, setActor] = useState<OperatorActor>('operator1')
  const [decisionReference, setDecisionReference] = useState('')
  const [recoveryBusy, setRecoveryBusy] = useState(false)
  const [recoveryMessage, setRecoveryMessage] = useState('')

  const [incidents, setIncidents] = useState<AnomalyState[]>([])
  const selectionEpoch = useRef(0)

  function selectSpacecraft(value: string) {
    selectionEpoch.current++
    setSpacecraftId(value)
    setLatch(null)
    setPhase('idle')
    setErrorText('')
    setCheckResult(null)
    setCheckError('')
    setCheckBusy(false)
    setRecoveryBusy(false)
    setRecoveryMessage('')
    setDecisionReference('')
  }

  async function loadLatch() {
    if (demoMode) return
    const epoch = ++selectionEpoch.current
    setLatch(null)
    setPhase('loading')
    setErrorText('')
    setCheckResult(null)
    try {
      const result = await anomalyApi.latch(spacecraftId)
      if (epoch !== selectionEpoch.current) return
      if (result.id !== spacecraftId) throw new Error('Safety response spacecraft mismatch')
      setLatch(result)
      setPhase('ready')
    } catch (error) {
      if (epoch !== selectionEpoch.current) return
      if (error instanceof ApiError && error.kind === 'not_found') setPhase('not_found')
      else {
        setPhase('error')
        setErrorText(errorMessage(error))
      }
      setLatch(null)
    }
  }

  async function loadIncidents() {
    if (demoMode) return
    try {
      setIncidents(await anomalyApi.incidents(20))
    } catch {
      /* non-fatal secondary list */
    }
  }

  useEffect(() => {
    const epochRef = selectionEpoch
    if (!demoMode) {
      loadLatch()
      loadIncidents()
    }
    return () => { epochRef.current++ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode])

  // Deliberately manual: /check has a side effect (may latch a new incident / emit events), so it
  // must never be polled automatically.
  async function runCheck() {
    if (demoMode || !latch || latch.id !== spacecraftId || checkBusy || recoveryBusy) return
    const epoch = selectionEpoch.current
    setCheckBusy(true)
    setCheckError('')
    try {
      const result = await anomalyApi.check(spacecraftId, actor)
      if (epoch !== selectionEpoch.current) return
      setCheckResult(result)
      setLatch({ id: spacecraftId, version: result.safetyVersion, body: result.latch })
    } catch (error) {
      if (epoch === selectionEpoch.current) setCheckError(errorMessage(error))
    } finally {
      if (epoch === selectionEpoch.current) setCheckBusy(false)
    }
  }

  async function requestRecovery() {
    if (demoMode || !latch || latch.id !== spacecraftId || !decisionReference.trim() || checkBusy || recoveryBusy) return
    const epoch = selectionEpoch.current
    const wasFrozen = latch.body.frozen
    const priorApprovals = latch.body.approvals.length
    setRecoveryBusy(true)
    setRecoveryMessage('')
    try {
      const result = await anomalyApi.requestRecovery(
        latch.id,
        latch.version,
        decisionReference,
        newIdempotencyKey(),
        actor,
      )
      if (epoch !== selectionEpoch.current) return
      if (result.state && result.state.id !== spacecraftId) throw new Error('Safety response spacecraft mismatch')
      if (!result.approved) {
        const reasons = result.check?.currentReasons.join(', ') ?? '알 수 없음'
        setRecoveryMessage(`거부: 현재 안전 사유가 남아있습니다 (${reasons}). 동결이 유지됩니다.`)
      } else if (!wasFrozen) {
        // SafetyLatch.approve is a no-op when the latch was not frozen (SafetyLatch.java:63) —
        // approved:true here means "nothing to approve", not that this submission freed anything.
        setRecoveryMessage('이미 동결 상태가 아니었습니다 — 이 제출로 바뀐 것은 없습니다.')
      } else if (result.state && result.state.body.frozen) {
        const gained = result.state.body.approvals.length - priorApprovals
        setRecoveryMessage(
          gained > 0
            ? `승인 1건 기록됨 — 동결은 아직 유지 중입니다 (현재 기록된 승인 ${result.state.body.approvals.length}건, 필요 인원은 서버가 노출하지 않아 표시 불가). ` +
                '이 버튼은 실제 운용 승인을 완료하지 않았습니다.'
            : '이 행위자(actor)는 이미 승인을 기록했습니다 — 추가로 반영된 것은 없습니다. 동결이 유지 중입니다.',
        )
      } else if (result.state) {
        setRecoveryMessage('필요 승인 인원 충족 — 동결이 해제되었습니다.')
      }
      if (result.state) setLatch(result.state)
      await loadIncidents()
    } catch (error) {
      if (epoch !== selectionEpoch.current) return
      if (error instanceof ApiError && error.kind === 'conflict') {
        setRecoveryMessage('승인 실패: 안전 상태 버전이 변경됨(충돌) — 다시 조회 후 재시도하세요.')
      } else {
        setRecoveryMessage('승인 요청 실패: ' + errorMessage(error))
      }
    } finally {
      if (epoch === selectionEpoch.current) setRecoveryBusy(false)
    }
  }

  if (demoMode) {
    return (
      <section className="panel-block">
        <h2>승인 · 안전</h2>
        <div className="demo-banner">데모 모드 — 실제 안전 상태가 아닙니다.</div>
        <div className="status-ok">동결 없음 (데모)</div>
      </section>
    )
  }

  return (
    <section className="panel-block">
      <h2>승인 · 안전</h2>
      <label htmlFor="auth-craft">위성 ID</label>
      <div className="inline-form">
        <input id="auth-craft" value={spacecraftId} onChange={(e) => selectSpacecraft(e.target.value)} />
        <button onClick={loadLatch} disabled={phase === 'loading' || checkBusy || recoveryBusy} aria-busy={phase === 'loading'}>
          조회
        </button>
      </div>

      {phase === 'loading' && <div className="hint">불러오는 중...</div>}
      {phase === 'not_found' && (
        <div className="status-warn" role="alert">
          안전 정책 미설정: 이 위성 ID에 등록된 SafetyPolicy가 없습니다.
        </div>
      )}
      {phase === 'error' && (
        <div className="status-error" role="alert">
          조회 실패: {errorText}
        </div>
      )}

      {phase === 'ready' && latch && latch.id === spacecraftId && (
        <>
          <dl>
            <dt>저장된 동결 상태 (최근 평가 시각 아님)</dt>
            <dd className={latch.body.frozen ? 'status-error' : 'status-ok'}>
              {latch.body.frozen ? '동결됨 (FROZEN)' : '동결 없음'}
            </dd>
            <dt>사유</dt>
            <dd>{latch.body.reasons.length ? latch.body.reasons.join(', ') : '없음'}</dd>
            <dt>generation / policyVersion</dt>
            <dd>
              {latch.body.generation} / {latch.body.policyVersion}
            </dd>
            <dt>기록된 승인</dt>
            <dd>
              {latch.body.approvals.length === 0
                ? '없음'
                : latch.body.approvals.map((a) => `${a.actor} (${a.decisionReference})`).join(', ')}
            </dd>
          </dl>

          <button onClick={runCheck} disabled={checkBusy || recoveryBusy} aria-busy={checkBusy}>
            지금 실시간 안전 평가 실행 (부수효과 있음, 자동 반복 없음)
          </button>
          {checkError && (
            <div className="status-error" role="alert">
              평가 실패: {checkError}
            </div>
          )}
          {checkResult && (
            <dl>
              <dt>평가 시각</dt>
              <dd>
                <TimeValue instant={checkResult.evaluatedAt} offset={offset} />
              </dd>
              <dt>결과</dt>
              <dd className={checkResult.clear ? 'status-ok' : 'status-error'}>
                {checkResult.clear ? '정상 (clear)' : '안전 사유 존재'}
              </dd>
              {!checkResult.clear && (
                <>
                  <dt>현재 사유</dt>
                  <dd>{checkResult.currentReasons.join(', ')}</dd>
                </>
              )}
            </dl>
          )}

          <h3>복구 승인 요청</h3>
          <div className="hint">
            승인 버튼은 실제 운용 승인을 자동 수행하지 않습니다 — 서버가 거부하거나 추가 승인이
            필요하다고 응답할 수 있습니다.
          </div>
          <label htmlFor="auth-actor">승인 행위자 (로컬 시뮬레이션 전용 계정)</label>
          <select id="auth-actor" value={actor} onChange={(e) => setActor(e.target.value as OperatorActor)}>
            <option value="operator1">operator1</option>
            <option value="operator2">operator2</option>
          </select>
          <label htmlFor="auth-reason">결정 근거 (decisionReference)</label>
          <input
            id="auth-reason"
            value={decisionReference}
            onChange={(e) => setDecisionReference(e.target.value)}
          />
          <button onClick={requestRecovery} disabled={checkBusy || recoveryBusy || !decisionReference.trim()} aria-busy={recoveryBusy}>
            복구 승인 제출 (expectedSafetyVersion={latch.version})
          </button>
          {recoveryMessage && (
            <p className="hint" aria-live="polite">
              {recoveryMessage}
            </p>
          )}
        </>
      )}

      <h3>최근 이상(anomaly) 기록</h3>
      {incidents.length === 0 && <div className="hint">기록 없음 또는 미조회</div>}
      {incidents.length > 0 && (
        <ul className="incident-list">
          {incidents.map((inc) => (
            <li key={inc.id}>
              <span className="mono">{inc.body.anomaly.spacecraftId.value}</span> —{' '}
              {inc.body.reasons.join(', ')} —{' '}
              <TimeValue instant={inc.body.anomaly.declaredAt} offset={offset} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
