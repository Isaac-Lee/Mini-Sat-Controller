import { useEffect, useState } from 'react'
import { loadPlanning, loadRunEvidence, loadExecution, apiProblem, type Evidence, type PlanningRun } from '../api/workflow'
import type { RequestState } from '../api/types'
import { EvidenceSummary } from './EvidenceSummary'
import { TimeValue } from './TimeValue'
import type { TaiUtcOffset } from '../time'

type ReadState<T> = { data?: T; error?: string }

function EvidenceCards({ items }: { items: Evidence[] }) {
  return <div className="evidence-grid">{items.map((item) => (
    <article className="evidence-card" key={item.title}>
      <h3>{item.title}</h3><p>{item.description}</p>
      {item.error ? <p role="alert" className="status-warn">{item.error}</p> : (
        <><EvidenceSummary data={item.data} /><details><summary>저장된 서버 근거 보기</summary><pre>{JSON.stringify(item.data, null, 2)}</pre></details></>
      )}
    </article>
  ))}</div>
}
function RunEvidence({ run, offset, onInspectCraft }: { run: PlanningRun; offset: TaiUtcOffset | null; onInspectCraft: (craft: string, safety: boolean) => void }) {
  const [state, setState] = useState<ReadState<Evidence[]>>({})
  useEffect(() => {
    let active = true
    loadRunEvidence(run.id).then((data) => { if (active) setState({ data }) })
    return () => { active = false }
  }, [run.id])
  return <>
    <p>계획 ID <span className="mono">{run.id}</span> · 요청 리비전 {run.requestRevision} · 위성 {run.spacecraftId}</p>
    <p>계획 시작 <TimeValue instant={run.run.startedAt} offset={offset} /></p>
    <div className="request-actions"><button onClick={() => onInspectCraft(run.spacecraftId, false)}>이 위성 모니터링</button><button onClick={() => onInspectCraft(run.spacecraftId, true)}>이 위성 안전 상태</button></div>
    <h3>촬영 후보 {run.run.candidates.length}개</h3>
    <p className="hint">후보는 확정 일정이 아닙니다. V1 샘플·근사 모델에 기반하며 실제 영상 품질을 보장하지 않습니다.</p>
    {run.run.candidates.map((candidate) => <article className="evidence-card" key={candidate.id.value}>
      <h4>후보 {candidate.id.value}</h4>
      <p>{candidate.phase} · {candidate.mode} · 활동 정의 v{candidate.activity.definitionVersion}</p>
      <p>시작 <TimeValue instant={candidate.activity.window.start} offset={offset} /></p>
      <p>종료 <TimeValue instant={candidate.activity.window.end} offset={offset} /></p>
      <details><summary>후보 타당성 근거</summary><pre>{JSON.stringify(candidate.feasibility, null, 2)}</pre></details>
    </article>)}
    {state.data ? <EvidenceCards items={state.data} /> : <p role="status">계획 근거 불러오는 중...</p>}
  </>
}
function RequestWorkflow({ selected, offset, onInspectCraft }: { selected: RequestState; offset: TaiUtcOffset | null; onInspectCraft: (craft: string, safety: boolean) => void }) {
  const [planning, setPlanning] = useState<ReadState<Awaited<ReturnType<typeof loadPlanning>>>>({})
  const [execution, setExecution] = useState<ReadState<Evidence[]>>({})
  const [runId, setRunId] = useState('')
  useEffect(() => {
    let active = true
    loadPlanning(selected.id).then((data) => { if (active) { setPlanning({ data }); setRunId(data.runs[0]?.id ?? '') } }, (error) => { if (active) setPlanning({ error: apiProblem(error) }) })
    loadExecution(selected.id).then((data) => { if (active) setExecution({ data }) }, (error) => { if (active) setExecution({ error: apiProblem(error) }) })
    return () => { active = false }
  }, [selected.id])
  const run = planning.data?.runs.find((item) => item.id === runId)
  return <>
    <p className="selection-summary">{selected.body.target} · {selected.body.request.status}<br /><span className="mono">{selected.id}</span></p>
    <section><h2>1. 요청 → 계획·촬영 후보</h2>
      <p>Tasking이 접수한 요청을 Planning이 궤도·임무 정의·관측 상태·안전 근거와 함께 평가합니다.</p>
      {planning.error && <p role="alert">{planning.error}</p>}
      {!planning.data && !planning.error && <p role="status">계획 불러오는 중...</p>}
      {planning.data && <>
        <p>입력 수집: {planning.data.intake.status} · 시도 {planning.data.intake.attempts}회</p>
        {planning.data.failures.map((error) => <p role="alert" key={error}>{error}</p>)}
        {planning.data.runs.length === 0 ? <p>최신 입력 시도에 등록된 계획이 없습니다. 입력 대기나 과거 요청일 수 있습니다.</p> : <>
          <label htmlFor="workflow-run">계획 선택</label>{' '}
          <select id="workflow-run" value={runId} onChange={(e) => setRunId(e.target.value)}>{planning.data.runs.map((item) => <option key={item.id} value={item.id}>{item.spacecraftId} · {item.id}</option>)}</select>
          {run && <RunEvidence key={run.id} run={run} offset={offset} onInspectCraft={onInspectCraft} />}
        </>}
      </>}
    </section>
    <section><h2>2. 일정·승인 → 촬영 → 지상국·다운링크 → 수신</h2>
      <p>이 구간은 위 계획 선택과 독립적으로, 완료 결과가 연결한 촬영/다운링크 명령을 조회합니다. 위 후보는 최신 입력 시도의 목록이며 실제 사용된 계획·후보 ID는 아래 일정 카드에 표시됩니다. 여기서 근거를 여는 동작은 명령 실행이나 승인을 수행하지 않습니다.</p>
      {execution.error && <p role="alert">실행 연결: {execution.error} 완료 결과가 등록된 요청에서 전체 연결을 확인할 수 있습니다.</p>}
      {!execution.data && !execution.error && <p role="status">실행 근거 불러오는 중...</p>}
      {execution.data && <EvidenceCards items={execution.data} />}
    </section>
    <section><h2>3. 결과 검토</h2><p>제품 탭에서 요청에 귀속된 합성 원본·진단 PNG와 해시를 확인합니다. FULFILLED라도 물리 영상 품질을 평가한 결과는 아닙니다.</p></section>
  </>
}
export function WorkflowPanel({ demoMode, selectedRequest, offset, onInspectCraft = () => {} }: { demoMode: boolean; selectedRequest: RequestState | null; offset: TaiUtcOffset | null; onInspectCraft?: (craft: string, safety: boolean) => void }) {
  const [revision, setRevision] = useState(0)
  return <div className="workflow-panel">
    <h1>임무 흐름 · 실제 저장 근거</h1>
    <p>요청부터 합성 결과까지 각 서비스가 무엇을 맡는지 확인합니다.</p>
    {demoMode ? <p className="demo-banner">데모 모드 — 서버 근거를 조회하지 않습니다. 전체 서비스 탭에서 역할 설명을 볼 수 있습니다.</p> : selectedRequest ? <>
      <button onClick={() => setRevision((value) => value + 1)}>흐름 새로고침</button>
      <RequestWorkflow key={`${selectedRequest.id}:${revision}`} selected={selectedRequest} offset={offset} onInspectCraft={onInspectCraft} />
    </> : <p className="hint">요청 탭에서 요청을 선택하세요. FULFILLED 요청을 선택하면 저장된 전체 실행 흐름을 확인할 수 있습니다.</p>}
  </div>
}
