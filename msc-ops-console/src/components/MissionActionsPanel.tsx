import { useRef, useState } from 'react'
import { isDemoBackend, newIdempotencyKey } from '../api/client'
import type { RequestState } from '../api/types'
import { buildSteps, problem, type Ctx, type Step } from '../missionSteps'

// Drives a NEW V1 SIMULATION mission from a selected request: confirm a candidate, prepare and
// approve commands, release/dispatch them to the simulator, receive simulated evidence, downlink
// synthetic bytes and register the synthetic result. Every effect is a simulation; nothing here
// commands hardware. Each step is one explicit operator click; outcomes are shown as the server
// returned them (UNKNOWN stays UNKNOWN).

// The server's own status/belief is the headline; only ACCEPTED / OBSERVED / confirmed effects (or
// a reply without such a field) count as success. UNKNOWN or REJECTED is never shown as done.
function verdict(shown: unknown): { ok: boolean; label: string } {
  const value = shown as { belief?: string; reason?: string; body?: { status?: string } } | null
  const status = value?.belief ?? value?.body?.status
  if (!status) return { ok: true, label: '응답 수신' }
  const ok = ['ACCEPTED', 'OBSERVED', 'SIMULATION_EFFECTS_CONFIRMED'].includes(status)
  return { ok, label: value?.reason ? `${status} (${value.reason})` : status }
}

type Outcome = { state: 'busy' } | { state: 'done'; shown: unknown } | { state: 'error'; message: string }

export function MissionActionsPanel({ selectedRequest }: { selectedRequest: RequestState | null }) {
  const [ctx, setCtx] = useState<Ctx>({ links: {} })
  const [outcomes, setOutcomes] = useState<Record<string, Outcome>>({})
  const [review, setReview] = useState('Console SIMULATION review')
  const [bookingId, setBookingId] = useState('')
  const keys = useRef(new Map<string, string>())

  if (isDemoBackend()) {
    return (
      <section className="panel-block">
        <h2>임무 조작 (SIMULATION)</h2>
        <div className="demo-banner">데모 모드에서는 임무 조작을 지원하지 않습니다 — 가짜 성공을 표시하지 않기 위해 실제 로컬 백엔드에서만 동작합니다.</div>
      </section>
    )
  }
  if (!selectedRequest) {
    return (
      <section className="panel-block">
        <h2>임무 조작 (SIMULATION)</h2>
        <div className="hint">요청 탭에서 조작할 요청을 먼저 선택하세요.</div>
      </section>
    )
  }

  const steps = buildSteps(selectedRequest.id, review, bookingId.trim())

  async function execute(step: Step) {
    // One key per attempt: kept after a failure so a retry after a lost response is deduplicated,
    // dropped after success so the next click of a repeatable step is a new attempt.
    const key = keys.current.get(step.id) ?? newIdempotencyKey()
    keys.current.set(step.id, key)
    setOutcomes((o) => ({ ...o, [step.id]: { state: 'busy' } }))
    try {
      const { patch, shown } = await step.run(ctx, key)
      keys.current.delete(step.id)
      if (patch) setCtx((c) => ({ ...c, ...patch }))
      setOutcomes((o) => ({ ...o, [step.id]: { state: 'done', shown } }))
    } catch (error) {
      setOutcomes((o) => ({ ...o, [step.id]: { state: 'error', message: problem(error) } }))
    }
  }

  return (
    <section className="panel-block">
      <h2>임무 조작 (SIMULATION)</h2>
      <div className="status-warn">
        모든 단계는 시뮬레이터 위의 합성 동작입니다. 실제 위성 명령·RF·영상이 아니며, 관리 단계(가짜 데이터)는 로컬 개발 계정 admin으로 실행됩니다.
        새로고침하면 화면의 진행 정보는 사라지지만 서버에 기록된 결과는 남습니다.
      </div>
      <label htmlFor="ma-review">검토 근거 (reviewReference)</label>
      <input id="ma-review" value={review} onChange={(e) => setReview(e.target.value)} />
      <label htmlFor="ma-booking">CONFIRMED 지상국 예약 ID (스테이션·예약 생성은 관리 스크립트로 준비)</label>
      <input id="ma-booking" value={bookingId} onChange={(e) => setBookingId(e.target.value)} />
      <ol className="mission-steps">
        {steps.map((step) => {
          const missing = step.needs(ctx)
          const outcome = outcomes[step.id]
          const done = outcome?.state === 'done'
          return (
            <li key={step.id} className={step.admin ? 'admin-step' : undefined}>
              <button
                onClick={() => execute(step)}
                disabled={!!missing || outcome?.state === 'busy' || (done && !step.repeatable)}
                aria-busy={outcome?.state === 'busy'}
              >
                {step.admin ? '[Simulation admin · 가짜 데이터] ' : ''}{step.title}
              </button>
              {missing && <span className="hint"> — {missing}</span>}
              {outcome?.state === 'error' && <div className="status-error" role="alert">{outcome.message}</div>}
              {done && (
                <details>
                  <summary className={verdict(outcome.shown).ok ? 'status-ok' : 'status-warn'}>
                    서버 응답: {verdict(outcome.shown).label} (펼쳐서 보기)
                  </summary>
                  <pre className="mono">{JSON.stringify(outcome.shown, null, 2)}</pre>
                </details>
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
