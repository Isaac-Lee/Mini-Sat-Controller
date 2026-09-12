import { useEffect, useState } from 'react'
import { ApiError, newIdempotencyKey } from '../api/client'
import { taskingApi } from '../api/tasking'
import type { RequestState, Submission } from '../api/types'
import { TimeValue } from './TimeValue'
import type { TaiUtcOffset } from '../time'
import { STATUS_CLASS, STATUS_LABEL } from '../requestStatus'

interface Props {
  selectedRequest: RequestState | null
  onOpenWorkflow: () => void
  demoMode: boolean
  offset: TaiUtcOffset | null
  onSelectRequest: (request: RequestState | null) => void
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `[${error.kind}] ${error.message}`
  return String(error)
}

export function RequestPanel({ demoMode, offset, onSelectRequest, selectedRequest, onOpenWorkflow }: Props) {
  const [items, setItems] = useState<RequestState[]>([])
  const [listPhase, setListPhase] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [listError, setListError] = useState('')

  const [target, setTarget] = useState('')
  const [priority, setPriority] = useState(50)
  const [submitBusy, setSubmitBusy] = useState(false)
  const [submitMessage, setSubmitMessage] = useState('')

  const [selectedId, setSelectedId] = useState<string | null>(selectedRequest?.id ?? null)
  const [submittedFields, setSubmittedFields] = useState<Submission | null>(null)
  const [actionMessage, setActionMessage] = useState('')

  async function loadList() {
    if (demoMode) return
    setListPhase('loading')
    setListError('')
    try {
      const page = await taskingApi.list()
      setItems(page.items)
      setListPhase('ready')
    } catch (error) {
      setListPhase('error')
      setListError(errorMessage(error))
    }
  }

  useEffect(() => {
    if (!demoMode) loadList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (demoMode) return
    setSubmitBusy(true)
    setSubmitMessage('')
    try {
      const submission: Submission = {
        target,
        area: null,
        criteria: null,
        deadline: null,
        priority,
        preference: 'AUTO',
      }
      await taskingApi.create(submission, newIdempotencyKey())
      setSubmitMessage('요청 제출됨 — 목표 이름 해석 대기 중일 수 있습니다.')
      setTarget('')
      await loadList()
    } catch (error) {
      setSubmitMessage('제출 실패: ' + errorMessage(error))
    } finally {
      setSubmitBusy(false)
    }
  }

  const selected = items.find((i) => i.id === selectedId) ?? null

  useEffect(() => {
    if (selected) onSelectRequest(selected)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected])

  async function loadSubmission(revision: number) {
    if (!selected) return
    try {
      const s = await taskingApi.submittedRevision(selected.id, revision)
      setSubmittedFields(s)
    } catch (error) {
      setActionMessage('제출 원본 조회 실패: ' + errorMessage(error))
    }
  }

  async function cancelSelected() {
    if (!selected) return
    setActionMessage('')
    try {
      await taskingApi.cancel(selected.id, selected.version, newIdempotencyKey())
      setActionMessage('취소 요청 완료 (미래 태스킹만 무효화, 이미 업링크된 명령 취소를 보장하지 않음).')
      await loadList()
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'conflict') {
        setActionMessage('취소 실패: 요청이 이미 변경됨(버전 충돌) — 목록을 새로고침하세요.')
      } else {
        setActionMessage('취소 실패: ' + errorMessage(error))
      }
    }
  }

  if (demoMode) {
    return (
      <section className="panel-block">
        <h2>촬영 요청</h2>
        <div className="demo-banner">데모 모드 — 실제 요청을 제출하지 않습니다.</div>
        <div className="hint">데모 예시 요청: Daejeon AOI, 우선순위 50, 상태 ACCEPTED (가상 데이터).</div>
      </section>
    )
  }

  return (
    <section className="panel-block">
      <h2>촬영 요청</h2>

      <form onSubmit={submit} className="request-form">
        <label htmlFor="req-target">목표 이름 또는 좌표 설명</label>
        <input
          id="req-target"
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          required
          maxLength={500}
        />
        <label htmlFor="req-priority">우선순위 (0-100)</label>
        <input
          id="req-priority"
          type="number"
          min={0}
          max={100}
          value={priority}
          onChange={(e) => setPriority(Number(e.target.value))}
        />
        <button type="submit" disabled={submitBusy || !target.trim()} aria-busy={submitBusy}>
          요청 제출
        </button>
      </form>
      {submitMessage && (
        <p className="hint" aria-live="polite">
          {submitMessage}
        </p>
      )}

      <h3>내 요청 목록</h3>
      <div className="request-actions">
        <button onClick={loadList} disabled={listPhase === 'loading'}>목록 새로고침</button>
        <button disabled={!items.some((item) => item.body.request.status === 'FULFILLED')} onClick={() => {
          const completed = items.find((item) => item.body.request.status === 'FULFILLED')
          if (completed) { onSelectRequest(completed); onOpenWorkflow() }
        }}>완료된 예시 열기</button>
      </div>
      {listPhase === 'loading' && <div className="hint">불러오는 중...</div>}
      {listPhase === 'error' && (
        <div className="status-error" role="alert">
          목록 조회 실패: {listError}
        </div>
      )}
      {listPhase === 'ready' && items.length === 0 && <div className="hint">요청 없음</div>}
      {listPhase === 'ready' && items.length > 0 && (
        <ul className="request-list">
          {items.map((item) => (
            <li key={item.id}>
              <button
                className={`request-row ${item.id === selectedId ? 'selected' : ''}`}
                onClick={() => {
                  setSelectedId(item.id)
                  setSubmittedFields(null)
                  setActionMessage('')
                }}
              >
                <span>{item.body.target}</span>
                <span className={STATUS_CLASS[item.body.request.status]}>
                  {STATUS_LABEL[item.body.request.status]}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {selected && (
        <div className="request-detail">
          <button onClick={onOpenWorkflow}>선택한 요청의 임무 흐름 보기</button>
          <dl>
            <dt>상태</dt>
            <dd className={STATUS_CLASS[selected.body.request.status]}>
              {STATUS_LABEL[selected.body.request.status]}
            </dd>
            <dt>수정본(revision)</dt>
            <dd>{selected.body.request.revision}</dd>
            <dt>생성 시각</dt>
            <dd>
              <TimeValue instant={selected.body.createdAt} offset={offset} />
            </dd>
            <dt>갱신 시각</dt>
            <dd>
              <TimeValue instant={selected.body.updatedAt} offset={offset} />
            </dd>
            <dt>유효 커버리지/구름 기준</dt>
            <dd>
              최소 커버리지 {selected.body.criteria.minimumCoverageFraction}, 최대 구름
              {selected.body.criteria.maximumCloudFraction}
            </dd>
            {selected.body.area && (
              <>
                <dt>확정 영역 출처</dt>
                <dd className="mono">{selected.body.area.sourceReference}</dd>
              </>
            )}
            {selected.body.reason && (
              <>
                <dt>최근 사유</dt>
                <dd>{selected.body.reason}</dd>
              </>
            )}
          </dl>
          <button onClick={() => loadSubmission(selected.body.request.revision)}>
            제출 원본 조회 (revision {selected.body.request.revision})
          </button>
          {submittedFields && (
            <dl>
              <dt>제출한 목표</dt>
              <dd>{submittedFields.target}</dd>
              <dt>제출한 우선순위</dt>
              <dd>{submittedFields.priority}</dd>
            </dl>
          )}
          {!selected.body.request.status.match(/^(FULFILLED|REJECTED|EXPIRED|CANCELLED)$/) && (
            <button onClick={cancelSelected}>요청 취소</button>
          )}
          {actionMessage && (
            <p className="hint" aria-live="polite">
              {actionMessage}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
