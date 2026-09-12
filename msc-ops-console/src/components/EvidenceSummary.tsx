// Only display fields present in the saved response; absence is never treated as success.
function at(value: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((node, key) =>
    node !== null && typeof node === 'object' ? (node as Record<string, unknown>)[key] : undefined, value)
}
const FIELDS = [
  ['환경', 'environment'], ['상태', 'status'], ['사유', 'reason'], ['평가 범위', 'scope'],
  ['일정 맥락', 'scheduleContextStatus'], ['공급 가정', 'supplyAssumption'],
  ['추진제 해석', 'propellantInterpretation'], ['검토자', 'reviewer'], ['검토 참조', 'reviewReference'],
  ['일정 버전', 'prepared.load.scheduleVersion'], ['위성', 'prepared.load.scheduleKey.spacecraftId.value'],
  ['방출 당시 안전 검사', 'checks.safety.clear'], ['시나리오', 'scenarioId'],
  ['제품 형식', 'format'], ['제품 크기 (bytes)', 'byteCount'],
  ['수신 완전성', 'acquisitionManifest.body.completeness'],
  ['예상 수신량 (bytes)', 'acquisitionManifest.body.expectedBytes'], ['실제 수신량 (bytes)', 'acquisitionManifest.body.receivedBytes'],
  ['시도 횟수', 'attempts'], ['최근 평가', 'last_assessment_id'], ['카메라 모델 버전', 'camera_model_version'], ['최근 이슈', 'last_issue'],
] as const
export function EvidenceSummary({ data }: { data: unknown }) {
  const body = at(data, 'body') ?? data
  const fields = FIELDS.flatMap(([label, path]) => {
    const value = at(body, path)
    return ['string', 'number', 'boolean'].includes(typeof value) ? [{ label, value: String(value) }] : []
  })
  const entries = at(body, 'ledger.body.entries')
  const candidates = at(body, 'candidates')
  const expected = at(body, 'acquisitionManifest.body.expected')
  const assignments = at(body, 'prepared.sources.schedule.assignments')
  return <>
    <dl className="evidence-summary">{fields.map(({ label, value }) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
    {Array.isArray(assignments) && <><h4>이 명령의 확정 일정 연결</h4>{assignments.map((item, n) => <dl key={n}>
      <dt>요청</dt><dd>{String(at(item, 'requestId.value') ?? '미제공')}</dd>
      <dt>계획</dt><dd>{String(at(item, 'runId.value') ?? '미제공')}</dd>
      <dt>선택된 후보</dt><dd>{String(at(item, 'candidateId.value') ?? '미제공')}</dd>
    </dl>)}</>}
    {Array.isArray(entries) && <ul>{entries.map((entry, n) => <li key={n}>
      {String(at(entry, 'catalog.template.operation') ?? '명령')} · {String(at(entry, 'status') ?? '상태 미제공')}
      {typeof at(entry, 'effect.actualDrainMegabytes') === 'number' && ` · 배출 ${at(entry, 'effect.actualDrainMegabytes')} MB`}
      {typeof at(entry, 'effect.generatedMegabytes') === 'number' && ` · 생성 ${at(entry, 'effect.generatedMegabytes')} MB`}
    </li>)}</ul>}
    {Array.isArray(candidates) && <ul>{candidates.map((item, n) => <li key={n}>후보 {String(at(item, 'candidateId') ?? n + 1)}: {at(item, 'cameraResult') ? `샘플 카메라 평가 · ${String(at(item, 'cameraResult.body.scope') ?? '범위 미제공')} · ${String(at(item, 'cameraResult.body.sampleCount') ?? '?')}개 샘플` : Array.isArray(at(item, 'issues')) ? (at(item, 'issues') as string[]).join(', ') || '기록된 이슈 없음 (확정 승인 아님)' : '이슈 정보 미제공'}</li>)}</ul>}
    {Array.isArray(expected) && <><h4>지상국 예약 → 수신 계획</h4>{expected.map((item, n) => <dl key={n}>
      <dt>지상국</dt><dd>{String(at(item, 'plan.body.booking.request.stationId') ?? '미제공')}</dd>
      <dt>예약 ID · 당시 상태</dt><dd>{String(at(item, 'plan.body.booking.id') ?? '미제공')} · {String(at(item, 'plan.body.booking.status') ?? '미제공')}</dd>
      <dt>수신 계획</dt><dd>{String(at(item, 'planId') ?? '미제공')}</dd>
      <dt>요청한 수신량 (MB)</dt><dd>{String(at(item, 'plan.body.booking.request.requestedMegabytes') ?? '미제공')}</dd>
    </dl>)}</>}
    {fields.length === 0 && !Array.isArray(entries) && !Array.isArray(candidates) && <p>이 응답은 아래 서버 근거에서 세부 내용을 확인할 수 있습니다.</p>}
  </>
}
