import { useEffect, useState } from 'react'
import { api, ApiError, newIdempotencyKey } from '../api/client'
import { planningApi, type PlanningAttempt, type PlanningWorkRow } from '../api/planning'
import type { AccessPrediction, OrbitSnapshot, RequestState, RequestStatus } from '../api/types'
import { TimeValue } from './TimeValue'
import type { TaiUtcOffset } from '../time'
import { STATUS_CLASS } from '../requestStatus'

const STATUS_ORDER: RequestStatus[] = [
  'RECEIVED',
  'CLARIFICATION_NEEDED',
  'ACCEPTED',
  'SCHEDULED',
  'PARTIALLY_FULFILLED',
  'FULFILLED',
]
const TERMINAL_OTHER: RequestStatus[] = ['REJECTED', 'EXPIRED', 'CANCELLED']

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `[${error.kind}] ${error.message}`
  return String(error)
}

interface Props {
  demoMode: boolean
  offset: TaiUtcOffset | null
  selectedRequest: RequestState | null
  flightOrbit: OrbitSnapshot | 'missing' | 'unknown'
}

export function TimelinePanel({ demoMode, offset, selectedRequest, flightOrbit }: Props) {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [prediction, setPrediction] = useState<AccessPrediction | null>(null)

  const [intake, setIntake] = useState<PlanningWorkRow | null>(null)
  const [intakePhase, setIntakePhase] = useState<'idle' | 'loading' | 'ready' | 'not_found' | 'error'>('idle')
  const [intakeError, setIntakeError] = useState('')
  const [attempt, setAttempt] = useState<PlanningAttempt | null>(null)
  const [attemptError, setAttemptError] = useState('')

  const area = selectedRequest?.body.area ?? null

  useEffect(() => {
    if (demoMode || !selectedRequest) {
      setIntake(null)
      setIntakePhase('idle')
      return
    }
    setIntakePhase('loading')
    setIntakeError('')
    setAttempt(null)
    planningApi
      .intakeStatus(selectedRequest.id)
      .then((row) => {
        setIntake(row)
        setIntakePhase('ready')
      })
      .catch((error) => {
        if (error instanceof ApiError && error.kind === 'not_found') setIntakePhase('not_found')
        else {
          setIntakePhase('error')
          setIntakeError(errorMessage(error))
        }
        setIntake(null)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode, selectedRequest?.id])

  async function loadAttempt(attemptId: string) {
    setAttemptError('')
    try {
      setAttempt(await planningApi.attempt(attemptId))
    } catch (error) {
      setAttemptError(errorMessage(error))
    }
  }

  async function computeAccess() {
    if (!area || typeof flightOrbit === 'string') return
    setBusy(true)
    setMessage('')
    setPrediction(null)
    try {
      const nowUtc = new Date().toISOString().replace(/\.\d+Z$/, '')
      const laterUtc = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().replace(/\.\d+Z$/, '')
      const start = await api.utcToTai(nowUtc)
      const end = await api.utcToTai(laterUtc)
      const centerLat = (area.south + area.north) / 2
      const centerLon = (area.west + area.east) / 2
      const result = await api.requestAccessPrediction(
        flightOrbit.id,
        {
          kind: 'POINT_IMAGING',
          target: { id: `${area.id}:center`, latitudeDegrees: centerLat, longitudeDegrees: centerLon, altitudeMeters: 0 },
          horizon: { start, end },
          minimumElevationDegrees: 10,
          maximumOffNadirDegrees: 30,
          minimumDurationSeconds: 1,
        },
        newIdempotencyKey(),
      )
      setPrediction(result.body)
      setMessage(`예상 접근 기회 ${result.body.windows.length}개 계산됨 (향후 24시간, AOI 중심점 기준).`)
    } catch (error) {
      setMessage('계산 실패: ' + errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  if (demoMode) {
    return (
      <section className="panel-block">
        <h2>타임라인</h2>
        <div className="demo-banner">데모 모드 — 아래는 가상의 확정 스케줄 예시입니다(실제 아님).</div>
        <ol className="request-status-axis">
          <li className="status-ok">RECEIVED</li>
          <li className="status-ok">ACCEPTED</li>
          <li className="status-ok">SCHEDULED (데모)</li>
          <li>FULFILLED</li>
        </ol>
      </section>
    )
  }

  return (
    <section className="panel-block">
      <h2>타임라인</h2>
      {!selectedRequest && <div className="hint">요청 탭에서 요청을 선택하면 상태 타임라인이 표시됩니다.</div>}
      {selectedRequest && (
        <>
          <h3>요청 생명주기 (실제 서버 상태)</h3>
          <ol className="request-status-axis">
            {STATUS_ORDER.map((s) => {
              const reached = selectedRequest.body.request.status === s
              return (
                <li
                  key={s}
                  className={reached ? 'status-ok current' : ''}
                  aria-current={reached ? 'step' : undefined}
                >
                  {s}
                </li>
              )
            })}
            {TERMINAL_OTHER.includes(selectedRequest.body.request.status) && (
              <li className={`${STATUS_CLASS[selectedRequest.body.request.status]} current`} aria-current="step">
                {selectedRequest.body.request.status}
              </li>
            )}
          </ol>

          <h3>계획 입력 수집 상태 (Planning intake — 확정 스케줄 아님)</h3>
          <div className="hint">
            아래는 계획 입력 수집 상태입니다. 백엔드는 후보 탐색과 시뮬레이션 스케줄 확정을
            지원하지만, 이 화면의 후보 검토·확정 스케줄 연결은 아직 구현 중입니다.
            요청의 합성 완료 결과는 제품 탭에서 확인할 수 있습니다.
          </div>
          {intakePhase === 'loading' && <div className="hint">불러오는 중...</div>}
          {intakePhase === 'not_found' && (
            <div className="status-warn">이 요청은 아직 Planning 큐에 유입되지 않았습니다.</div>
          )}
          {intakePhase === 'error' && (
            <div className="status-error" role="alert">
              조회 실패: {intakeError}
            </div>
          )}
          {intakePhase === 'ready' && intake && (
            <>
              <dl>
                <dt>intake 상태</dt>
                <dd>{intake.status}</dd>
                <dt>시도 횟수</dt>
                <dd>{intake.attempts}</dd>
                <dt>다음 시도 예정</dt>
                <dd>{intake.next_attempt_at ?? '없음'}</dd>
              </dl>
              {intake.last_attempt_id && (
                <button onClick={() => loadAttempt(intake.last_attempt_id!)}>
                  마지막 시도의 수집 증거 조회 (OPERATOR 권한 필요)
                </button>
              )}
              {attemptError && (
                <div className="status-error" role="alert">
                  {attemptError}
                </div>
              )}
              {attempt && (
                <dl>
                  <dt>시도 상태</dt>
                  <dd>{attempt.status}</dd>
                  <dt>수집 시각</dt>
                  <dd>
                    <TimeValue instant={attempt.capturedAt} offset={offset} />
                  </dd>
                  <dt>이슈</dt>
                  <dd>{attempt.issues.length ? attempt.issues.join(', ') : '없음'}</dd>
                  <dt>자산별 미확보 입력</dt>
                  <dd>
                    {attempt.assets.length === 0
                      ? '없음'
                      : attempt.assets.map((a) => `${a.spacecraftId}: ${a.missing.join(', ') || '없음'}`).join(' / ')}
                  </dd>
                </dl>
              )}
            </>
          )}

          <h3>예상 촬영/접촉 기회 (점 접근, AOI 커버리지 아님)</h3>
          {!area && <div className="hint">이 요청은 아직 영역(AOI)이 확정되지 않았습니다.</div>}
          {area && typeof flightOrbit === 'string' && (
            <div className="hint">
              지도 탭에서 공개 궤도를 가져오기(import)해야 접근 기회를 계산할 수 있습니다.
            </div>
          )}
          {area && typeof flightOrbit !== 'string' && (
            <>
              <button onClick={computeAccess} disabled={busy} aria-busy={busy}>
                점 접근 기회 계산 (향후 24시간)
              </button>
              {message && (
                <p className="hint" aria-live="polite">
                  {message}
                </p>
              )}
              {prediction && (
                <ul className="access-window-list">
                  {prediction.windows.length === 0 && <li className="hint">이 기간 내 접근 기회 없음</li>}
                  {prediction.windows.map((w, i) => (
                    <li key={i}>
                      <TimeValue instant={w.start} offset={offset} /> —{' '}
                      <TimeValue instant={w.end} offset={offset} />
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </>
      )}
    </section>
  )
}
