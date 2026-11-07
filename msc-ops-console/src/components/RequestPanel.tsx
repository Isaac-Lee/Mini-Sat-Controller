import { useEffect, useRef, useState } from 'react'
import { api, ApiError, newIdempotencyKey } from '../api/client'
import { taskingApi } from '../api/tasking'
import type { Area, Place, RequestState, Submission } from '../api/types'
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

const TERMINAL = /^(FULFILLED|REJECTED|EXPIRED|CANCELLED)$/

// Revising emits ObservationRequestInvalidated, so planning drops work already made for it.
const PLANNED = /^(SCHEDULED|PARTIALLY_FULFILLED)$/

function areaText(area: Area): string {
  return `W ${area.west}, S ${area.south}, E ${area.east}, N ${area.north}`
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

  const [placeResult, setPlaceResult] = useState<{ target: string; places: Place[] } | null>(null)
  const [placeError, setPlaceError] = useState('')
  const placeQuery = useRef('')

  const [reviseTarget, setReviseTarget] = useState(selectedRequest?.body.target ?? '')
  const [revisePriority, setRevisePriority] = useState(selectedRequest?.body.request.requestedPriority ?? 50)
  const [dropScheduleAck, setDropScheduleAck] = useState(false)
  const [reviseBusy, setReviseBusy] = useState(false)
  // One key per distinct revision payload, so a retry after a lost response is deduplicated.
  const reviseKeys = useRef(new Map<string, string>())
  const reviseInFlight = useRef(false)

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

  async function checkPlace() {
    const query = target.trim()
    if (demoMode || !query) return
    placeQuery.current = query
    setPlaceResult(null)
    setPlaceError('')
    try {
      const places = await api.places(query)
      if (placeQuery.current === query) setPlaceResult({ target: query, places })
    } catch (error) {
      if (placeQuery.current === query) setPlaceError(errorMessage(error))
    }
  }

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
      placeQuery.current = ''
      setPlaceResult(null)
      setPlaceError('')
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

  function selectRow(item: RequestState) {
    setSelectedId(item.id)
    setSubmittedFields(null)
    setActionMessage('')
    setReviseTarget(item.body.target)
    setRevisePriority(item.body.request.requestedPriority)
    setDropScheduleAck(false)
  }

  // Sends the stored submission of the current revision back unchanged apart from the given
  // fields; a clarification area must be the server's option object itself (record equality).
  async function revise(changes: Partial<Pick<Submission, 'target' | 'priority' | 'area'>>) {
    if (!selected || reviseInFlight.current) return
    if (PLANNED.test(selected.body.request.status) && !dropScheduleAck) return
    reviseInFlight.current = true
    setReviseBusy(true)
    setActionMessage('')
    try {
      const original = await taskingApi.submittedRevision(selected.id, selected.body.request.revision)
      const submission: Submission = { ...original, ...changes }
      const payloadKey = JSON.stringify([selected.id, selected.version, submission])
      const key = reviseKeys.current.get(payloadKey) ?? newIdempotencyKey()
      reviseKeys.current.set(payloadKey, key)
      await taskingApi.revise(selected.id, selected.version, submission, key)
      setActionMessage('수정본 제출됨 — 새 revision으로 처음(RECEIVED)부터 다시 처리됩니다.')
      setDropScheduleAck(false)
      await loadList()
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'conflict') {
        setActionMessage('수정 실패: 요청이 이미 변경되었거나 종료됨(충돌) — 목록을 새로 불러왔습니다. 입력은 유지됩니다.')
        await loadList()
      } else if (error instanceof ApiError && error.kind === 'invalid' && /deadline/i.test(error.message)) {
        setActionMessage('수정 실패: 원 제출의 마감시각이 이미 지났습니다. 마감시각은 임의로 지우지 않습니다.')
      } else {
        setActionMessage('수정 실패: ' + errorMessage(error))
      }
    } finally {
      reviseInFlight.current = false
      setReviseBusy(false)
    }
  }

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
          onChange={(e) => {
            setTarget(e.target.value)
            placeQuery.current = ''
            setPlaceResult(null)
            setPlaceError('')
          }}
          required
          maxLength={500}
        />
        <button type="button" onClick={checkPlace} disabled={!target.trim()}>
          승인 지명 확인
        </button>
        <div className="hint">
          자동완성이 아닙니다 — 승인된 지명 별칭과 정확히 일치(공백·대소문자 정규화)하는지만 확인합니다.
        </div>
        {placeError && (
          <div className="status-error" role="alert">
            지명 확인 실패: {placeError}
          </div>
        )}
        {placeResult && (
          <div aria-live="polite">
            {placeResult.places.length === 0 && (
              <div className="status-warn">
                승인 지명 없음 — 이대로 제출하면 영역을 확정할 수 없어 확인 요청(CLARIFICATION) 상태가 됩니다.
              </div>
            )}
            {placeResult.places.length === 1 && (
              <div className="status-ok">
                하나로 확정 가능: <span className="mono">{placeResult.places[0].id}:{placeResult.places[0].version}</span>{' '}
                ({areaText(placeResult.places[0].area)}, 승인 근거 {placeResult.places[0].approvalReference})
              </div>
            )}
            {placeResult.places.length > 1 && (
              <div className="status-warn">
                {placeResult.places.length > 100 ? '100곳 초과' : `${placeResult.places.length}곳`} 일치 — 제출 후
                서버가 제시하는 영역 중 하나를 골라야 합니다.
                <ul>
                  {placeResult.places.slice(0, 100).map((place) => (
                    <li key={`${place.id}:${place.version}`}>
                      <span className="mono">{place.id}:{place.version}</span> — {areaText(place.area)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
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
                onClick={() => selectRow(item)}
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
          {selected.body.request.status === 'CLARIFICATION_NEEDED' && selected.body.clarificationOptions.length > 0 && (
            <>
              <h4>영역 확인 필요 — 서버가 제시한 승인 영역</h4>
              <ul className="request-list">
                {selected.body.clarificationOptions.map((option) => (
                  <li key={option.id}>
                    <span className="mono">{option.id}</span> — {areaText(option)}{' '}
                    <button onClick={() => revise({ area: option })} disabled={reviseBusy}>
                      이 영역으로 확정
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
          {!TERMINAL.test(selected.body.request.status) && (
            <form
              className="request-form"
              onSubmit={(e) => {
                e.preventDefault()
                revise({ target: reviseTarget, priority: revisePriority })
              }}
            >
              <h4>요청 수정 (revision {selected.body.request.revision} → {selected.body.request.revision + 1})</h4>
              <label htmlFor="rev-target">목표</label>
              <input
                id="rev-target"
                value={reviseTarget}
                onChange={(e) => setReviseTarget(e.target.value)}
                required
                maxLength={500}
              />
              <label htmlFor="rev-priority">우선순위 (0-100)</label>
              <input
                id="rev-priority"
                type="number"
                min={0}
                max={100}
                value={revisePriority}
                onChange={(e) => setRevisePriority(Number(e.target.value))}
              />
              <div className="hint">
                영역·기준·마감시각은 현재 제출 원본을 그대로 유지합니다. 수정본은 처음(RECEIVED)부터 다시 처리됩니다.
              </div>
              {PLANNED.test(selected.body.request.status) && (
                <label>
                  <input
                    type="checkbox"
                    checked={dropScheduleAck}
                    onChange={(e) => setDropScheduleAck(e.target.checked)}
                  />{' '}
                  이미 만들어진 계획·일정이 무효화됨을 이해했습니다
                </label>
              )}
              <button
                type="submit"
                disabled={reviseBusy || !reviseTarget.trim() || (PLANNED.test(selected.body.request.status) && !dropScheduleAck)}
                aria-busy={reviseBusy}
              >
                수정본 제출 (expectedVersion={selected.version})
              </button>
            </form>
          )}
          {!TERMINAL.test(selected.body.request.status) && (
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
