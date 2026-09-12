import { request, ApiError } from './client'
import { planningApi } from './planning'
import { productsApi } from './products'
import type { MissionInstant, TimeWindow } from './types'

export interface Candidate {
  id: { value: string }
  activity: { window: TimeWindow; definitionId: { value: string }; definitionVersion: number }
  feasibility: unknown
  phase: string
  mode: string
}
export interface PlanningRun {
  id: string
  requestId: string
  requestRevision: number
  spacecraftId: string
  sourceHashes: Record<string, string>
  run: { startedAt: MissionInstant; candidates: Candidate[]; opportunities: unknown[]; decisions: unknown[] }
}
export interface Evidence {
  title: string
  description: string
  data?: unknown
  error?: string
}
const id = encodeURIComponent
const read = (service: string, path: string) => request<unknown>(`/fe-api/${service}${path}`)
export const workflowApi = {
  runs: (attempt: string) => request<{ runIds: string[] }>(`/fe-api/planning/api/planning/input-attempts/${id(attempt)}/runs`),
  run: (run: string) => request<PlanningRun>(`/fe-api/planning/api/planning/runs/${id(run)}`),
}
export function apiProblem(error: unknown) {
  if (error instanceof ApiError) {
    if (error.kind === 'not_found') return '등록된 근거가 없거나 현재 계정으로 조회할 수 없습니다 (404).'
    return `[${error.kind}] ${error.message}`
  }
  return '조회에 실패했습니다.'
}
async function evidence(title: string, description: string, fetch: () => Promise<unknown>): Promise<Evidence> {
  try { return { title, description, data: await fetch() } }
  catch (error) { return { title, description, error: apiProblem(error) } }
}

export async function loadPlanning(requestId: string) {
  const intake = await planningApi.intakeStatus(requestId)
  if (!intake.last_attempt_id) return { intake, runs: [] as PlanningRun[], failures: [] as string[] }
  const index = await workflowApi.runs(intake.last_attempt_id)
  const settled = await Promise.allSettled(index.runIds.map(workflowApi.run))
  const runs: PlanningRun[] = []
  const failures: string[] = []
  settled.forEach((item, n) => {
    if (item.status === 'rejected') failures.push(`${index.runIds[n]}: ${apiProblem(item.reason)}`)
    else if (item.value.requestId !== requestId) failures.push('다른 요청의 계획 응답을 제외했습니다.')
    else runs.push(item.value)
  })
  return { intake, runs, failures }
}
export async function loadRunEvidence(runId: string): Promise<Evidence[]> {
  const cards = await Promise.all([
    evidence('자원 예측', '배터리·저장소·추진제의 모델 기반 평가입니다. 물리 검증이나 자원 예약을 뜻하지 않습니다.', () => read('planning', `/api/planning/runs/${id(runId)}/resources`)),
    evidence('카메라 평가 작업', '샘플 카메라 평가의 진행과 평가 ID를 확인합니다. 연속 노출 정확도는 보장하지 않습니다.', () => read('planning', `/api/planning/runs/${id(runId)}/camera-work`)),
    evidence('조명 평가 작업', '궤도·태양 조건에 대한 저장된 평가 작업 상태입니다.', () => read('planning', `/api/planning/runs/${id(runId)}/illumination-work`)),
  ])
  const camera = cards[1].data as { camera_model_version?: number } | undefined
  if (camera?.camera_model_version && Number.isSafeInteger(camera.camera_model_version) && camera.camera_model_version > 0) {
    cards.push(await evidence('샘플 카메라 결과', '계획이 고정한 카메라 버전의 후보별 투영 평가입니다. 정밀 자세나 연속 촬영 검증을 뜻하지 않습니다.', () => read('planning', `/api/planning/runs/${id(runId)}/camera/${camera.camera_model_version}`)))
  }
  return cards
}

export async function loadExecution(requestId: string): Promise<Evidence[]> {
  const { body: result } = await productsApi.result(requestId)
  if (result.requestId !== requestId || result.environment !== 'SIMULATION') throw new Error('Result identity mismatch')
  const cards = await Promise.all([result.imageLoadId, result.downlinkLoadId].flatMap((load, n) => {
    const label = n === 0 ? '촬영' : '다운링크'
    return [
      evidence(`${label} 일정·승인·방출`, '요청/후보가 연결된 일정, 승인 검사, 시나리오와 전달 당시의 근거입니다.', () => read('control', `/api/command-loads/${id(load)}/simulation-release`)),
      evidence(`${label} 전달 상태`, '전달 당시의 원장입니다. PENDING은 이 기록 시점의 상태이며 현재 실행 여부는 다음 실행 확인에서 판단합니다. UNKNOWN은 실패 확정이 아닙니다.', () => read('control', `/api/command-loads/${id(load)}/simulation-delivery`)),
      evidence(`${label} 실행 확인`, '시뮬레이터의 명령 원장·수신·연결 상태와 요청 연결을 확인합니다.', () => read('control', `/api/command-loads/${id(load)}/simulation-execution`)),
    ]
  }))
  cards.push(await evidence('수신·제품 출처', 'Product가 보존한 Acquisition 출처·바이트·해시입니다. 다운로드는 제품 탭에서 제공합니다.', () => read('product', `/api/products/simulation-source-packages/${id(result.productId)}`)))
  return cards
}
