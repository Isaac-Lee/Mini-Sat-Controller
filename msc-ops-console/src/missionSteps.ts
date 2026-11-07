import { actionsApi, seconds, type Prepared } from './api/actions'
import { api, ApiError } from './api/client'
import type { MissionInstant } from './api/types'
import { loadPlanning, type PlanningRun } from './api/workflow'

// Step definitions of the console-driven V1 SIMULATION flow (MissionActionsPanel). Each step's
// inputs come from earlier server responses; nothing here invents evidence.

/* eslint-disable @typescript-eslint/no-explicit-any */
export interface Ctx {
  run?: PlanningRun & Record<string, any>
  candidate?: Record<string, any>
  cameraModelVersion?: number
  schedule?: { key: unknown; version: number }
  correlation?: { version: number; ticksPerSecond: number }
  image?: Prepared
  deadline?: MissionInstant
  scenarioId?: string
  links: Record<string, number>
  booking?: { id: string; window: any }
  downlinkOperation?: { activityId: string; schedule: { key: unknown; version: number } }
  downlink?: Prepared
  downlinkDeadline?: MissionInstant
  planId?: string
  manifestId?: string
  productId?: string
}

export interface Step {
  id: string
  title: string
  admin?: boolean
  repeatable?: boolean
  needs: (c: Ctx) => string | null
  run: (c: Ctx, key: string) => Promise<{ patch?: Partial<Ctx>; shown: unknown }>
}

const MD = 'sim-v1' // mission definition version used by the V1 simulation fixtures
const PROVENANCE = 'Console-driven SIMULATION step; synthetic data, no hardware claims'

export function problem(error: unknown): string {
  if (error instanceof ApiError) return `[${error.kind}${error.status ? ' ' + error.status : ''}] ${error.message}`
  return error instanceof Error ? error.message : String(error)
}

async function nowTai(): Promise<MissionInstant> {
  return api.utcToTai(new Date().toISOString().replace(/\.\d+Z$/, ''))
}

async function requireBefore(deadline: MissionInstant) {
  const now = await nowTai()
  const left = deadline.seconds - now.seconds
  if (left <= 0) throw new Error(`승인 유효 기한(validUntil)이 이미 지났습니다 (${-left}s 경과). 새 일정이 필요합니다.`)
  return left
}

function authorityRule(actionClass: 'IMAGE' | 'DOWNLINK') {
  return { context: { actionClass, phase: 'ROUTINE', spacecraftMode: 'NOMINAL', risk: 'LOW' }, requirement: 'HUMAN_APPROVAL' }
}

async function publishRule(craft: string, actionClass: 'IMAGE' | 'DOWNLINK', key: string) {
  let current: Awaited<ReturnType<typeof actionsApi.authorityPolicy>> | null = null
  try {
    current = await actionsApi.authorityPolicy(craft)
  } catch (error) {
    if (!(error instanceof ApiError && error.kind === 'not_found')) throw error
  }
  const rules = current ? [...current.body.rules, authorityRule(actionClass)] : [authorityRule(actionClass)]
  const policy = current ? { ...current.body, rules } : { spacecraftId: craft, missionDefinitionVersion: MD, rules, provenance: PROVENANCE }
  return actionsApi.publishAuthority({ expectedVersion: current?.version ?? 0, policy }, key)
}

function prepareBody(c: Ctx, prefix: string, schedule: { key: unknown; version: number }, activity: string, catalog: any, deadline: MissionInstant) {
  return {
    id: { value: `${prefix}-${c.run!.requestId}-${c.run!.id}`.slice(0, 120) },
    schedule: schedule.key,
    scheduleVersion: schedule.version,
    correlationVersion: c.correlation!.version,
    catalogsByActivity: { [activity]: { catalogId: catalog.id, catalogVersion: catalog.version } },
    parametersByActivity: { [activity]: {} },
    deadline,
  }
}

async function advanceThrough(c: Ctx, load: Prepared, catalog: any, key: string) {
  const scenario = await actionsApi.scenario(c.scenarioId!)
  const targetTick = load.body.load.commands[0].timeTag.ticks + Math.round(catalog.durationSeconds * c.correlation!.ticksPerSecond)
  return actionsApi.advance(c.scenarioId!, { expectedVersion: scenario.version, targetTick }, key)
}

async function setLink(c: Ctx, loadId: string, connected: boolean, key: string) {
  const expectedVersion = c.links[loadId] ?? 0
  await actionsApi.link(c.scenarioId!, loadId, { expectedVersion, connected, acknowledgmentLost: false, notBeforeTick: 0, provenance: PROVENANCE }, key)
  return { patch: { links: { ...c.links, [loadId]: expectedVersion + 1 } }, shown: { connected, linkVersion: expectedVersion + 1 } }
}

export function buildSteps(requestId: string, review: string, bookingId: string): Step[] {
  const imageCatalog = (c: Ctx) => c.run!.catalog?.value
  const downlinkCatalog = (c: Ctx) => c.run!.operations?.catalogs?.DOWNLINK?.value
  return [
    {
      id: 'load-run', title: '최신 계획 run과 첫 후보 불러오기', needs: () => null,
      run: async () => {
        const { runs } = await loadPlanning(requestId)
        const run = runs[runs.length - 1] as Ctx['run']
        if (!run) throw new Error('이 요청의 계획 run이 아직 없습니다.')
        const candidate = (run.run.candidates as any[])[0]
        if (!candidate) throw new Error('후보가 없습니다.')
        const work = await actionsApi.cameraWork(run.id)
        if (!work.camera_model_version) throw new Error('카메라 평가가 아직 끝나지 않았습니다.')
        return { patch: { run, candidate, cameraModelVersion: work.camera_model_version }, shown: { runId: run.id, candidateId: candidate.id.value, craft: run.spacecraftId } }
      },
    },
    {
      id: 'commit', title: '후보를 시뮬레이션 일정으로 확정 (simulation-commit)', needs: (c) => (c.candidate ? null : '후보를 먼저 불러오세요'),
      run: async (c, key) => {
        const r = await actionsApi.commit(c.run!.id, { candidateId: c.candidate!.id.value, cameraModelVersion: c.cameraModelVersion!, expectedScheduleVersion: 0, reviewReference: review }, key)
        return { patch: { schedule: r.body.schedule }, shown: r }
      },
    },
    {
      id: 'correlation', title: '시뮬레이션 시계 상관 등록', admin: true, needs: (c) => (c.schedule ? null : '일정 확정 후'),
      run: async (c, key) => {
        const start = c.candidate!.activity.window.start as MissionInstant
        const epoch = seconds(start, -60)
        const r = await actionsApi.correlation({
          expectedVersion: 0,
          correlation: { spacecraftId: c.run!.spacecraftId, missionDefinitionVersion: MD, timeCorrelationId: 'simulation-time', clockPartition: MD, taiEpoch: epoch, tickEpoch: 0, ticksPerSecond: 10, validInterval: { start: epoch, end: seconds(start, 3600) }, environment: 'SIMULATION', approvalReference: review, provenance: PROVENANCE },
        }, key)
        return { patch: { correlation: { version: r.version, ticksPerSecond: 10 }, deadline: seconds(start, -30) }, shown: r }
      },
    },
    {
      id: 'prepare-image', title: '촬영 명령 준비', needs: (c) => (c.correlation ? null : '시계 상관 등록 후'),
      run: async (c, key) => {
        const r = await actionsApi.prepare(prepareBody(c, 'console-image', c.schedule!, c.candidate!.activity.id.value, imageCatalog(c), c.deadline!), key)
        return { patch: { image: r }, shown: { loadId: r.id, checksum: r.body.load.checksum } }
      },
    },
    {
      id: 'approve-image', title: '촬영 명령 운영자 승인 (validUntil = 촬영 30초 전)', needs: (c) => (c.image ? null : '명령 준비 후'),
      run: async (c, key) => {
        const left = await requireBefore(c.deadline!)
        await actionsApi.approve(c.image!.id, { checksum: c.image!.body.load.checksum, validUntil: c.deadline!, expectedVersion: 0 }, key)
        return { shown: { approved: true, secondsLeftBeforeValidUntil: left } }
      },
    },
    {
      id: 'check-image', title: '현재 일정 검사 (schedule-check)', needs: (c) => (c.image ? null : '명령 준비 후'),
      run: async (c) => {
        const r = await actionsApi.scheduleCheck(c.image!.id)
        if (r.reasons.length) throw new Error('일정 검사 실패: ' + r.reasons.join(', '))
        return { shown: r }
      },
    },
    {
      id: 'authority-image', title: '권한 정책: IMAGE에 사람 승인 요구', admin: true, needs: (c) => (c.run ? null : 'run 불러오기 후'),
      run: async (c, key) => ({ shown: await publishRule(c.run!.spacecraftId, 'IMAGE', key) }),
    },
    {
      id: 'scenario', title: '시뮬레이터 시나리오 생성 (가짜 위성 상태)', admin: true, needs: (c) => (c.correlation ? null : '시계 상관 등록 후'),
      run: async (c, key) => {
        const scenarioId = crypto.randomUUID()
        const r = await actionsApi.createScenario({ id: scenarioId, spacecraftId: c.run!.spacecraftId, correlationVersion: c.correlation!.version, resourceProfilesVersion: 1, initialTick: 0, reservoirs: { storedMegabytes: 1, propellantKilograms: 1 }, provenance: PROVENANCE }, key)
        return { patch: { scenarioId }, shown: r }
      },
    },
    {
      id: 'release-image', title: '촬영 명령 방출 (simulation-release) — 안전 latch가 동결이면 승인·안전 탭에서 먼저 해제', needs: (c) => (c.image && c.scenarioId ? null : '승인·시나리오 후'),
      run: async (c, key) => ({ shown: await actionsApi.release(c.image!.id, { scenarioId: c.scenarioId!, checksum: c.image!.body.load.checksum, reviewReference: review }, key) }),
    },
    {
      id: 'dispatch-image', title: '시뮬레이터로 전달 (simulation-dispatch)', repeatable: true, needs: (c) => (c.scenarioId && c.image ? null : '방출 후'),
      run: async (c) => ({ shown: await actionsApi.dispatch(c.image!.id) }),
    },
    {
      id: 'advance-image', title: '가상 시계를 촬영 완료까지 진행', admin: true, needs: (c) => (c.scenarioId && c.image ? null : '전달 후'),
      run: async (c, key) => ({ shown: await advanceThrough(c, c.image!, imageCatalog(c), key) }),
    },
    {
      id: 'link-image', title: '시뮬레이션 링크 연결 (촬영 명령)', admin: true, needs: (c) => (c.scenarioId && c.image ? null : '전달 후'),
      run: (c, key) => setLink(c, c.image!.id, true, key),
    },
    {
      id: 'receive-image', title: '시뮬레이션 수신 시도 (RECONCILIATION)', repeatable: true, needs: (c) => (c.image ? null : '전달 후'),
      run: async (c, key) => ({ shown: await actionsApi.reception(c.image!.id, 'RECONCILIATION', key) }),
    },
    {
      id: 'reconcile-image', title: '실행 근거 대조 (reconcile)', repeatable: true, needs: (c) => (c.image && c.scenarioId ? null : '수신 후'),
      run: async (c, key) => {
        await actionsApi.evidence(c.scenarioId!, c.image!.id)
        return { shown: await actionsApi.reconcile(c.image!.id, key) }
      },
    },
    {
      id: 'booking', title: `지상국 예약 확인 (예약 ID: ${bookingId || '입력 필요'})`, needs: (c) => (!bookingId ? '아래에 CONFIRMED 예약 ID를 입력하세요' : c.run ? null : 'run 불러오기 후'),
      run: async () => {
        const b = await actionsApi.booking(bookingId)
        if (b.status !== 'CONFIRMED') throw new Error(`예약 상태가 ${b.status}입니다 (CONFIRMED 필요).`)
        return { patch: { booking: { id: bookingId, window: b.request.window } }, shown: b }
      },
    },
    {
      id: 'commit-downlink', title: '다운링크 일정 확정 (simulation-downlink)', needs: (c) => (c.booking && c.schedule ? null : '예약 확인·촬영 일정 후'),
      run: async (c, key) => {
        const r = await actionsApi.commitDownlink(c.run!.id, { bookingId: c.booking!.id, window: c.booking!.window, expectedScheduleVersion: 0, reviewReference: review }, key)
        return { patch: { downlinkOperation: r.body, downlinkDeadline: seconds(c.booking!.window.start, -10) }, shown: r }
      },
    },
    {
      id: 'prepare-downlink', title: '다운링크 명령 준비', needs: (c) => (c.downlinkOperation ? null : '다운링크 일정 후'),
      run: async (c, key) => {
        const r = await actionsApi.prepare(prepareBody(c, 'console-downlink', c.downlinkOperation!.schedule, c.downlinkOperation!.activityId, downlinkCatalog(c), c.downlinkDeadline!), key)
        return { patch: { downlink: r }, shown: { loadId: r.id, checksum: r.body.load.checksum } }
      },
    },
    {
      id: 'approve-downlink', title: '다운링크 명령 운영자 승인', needs: (c) => (c.downlink ? null : '다운링크 준비 후'),
      run: async (c, key) => {
        const left = await requireBefore(c.downlinkDeadline!)
        await actionsApi.approve(c.downlink!.id, { checksum: c.downlink!.body.load.checksum, validUntil: c.downlinkDeadline!, expectedVersion: 0 }, key)
        return { shown: { approved: true, secondsLeftBeforeValidUntil: left } }
      },
    },
    {
      id: 'authority-downlink', title: '권한 정책: DOWNLINK에 사람 승인 요구', admin: true, needs: (c) => (c.run ? null : 'run 불러오기 후'),
      run: async (c, key) => ({ shown: await publishRule(c.run!.spacecraftId, 'DOWNLINK', key) }),
    },
    {
      id: 'release-downlink', title: '다운링크 명령 방출', needs: (c) => (c.downlink && c.scenarioId ? null : '승인 후'),
      run: async (c, key) => ({ shown: await actionsApi.release(c.downlink!.id, { scenarioId: c.scenarioId!, checksum: c.downlink!.body.load.checksum, reviewReference: review }, key) }),
    },
    {
      id: 'dispatch-downlink', title: '다운링크 명령 전달', repeatable: true, needs: (c) => (c.downlink && c.scenarioId ? null : '방출 후'),
      run: async (c) => ({ shown: await actionsApi.dispatch(c.downlink!.id) }),
    },
    {
      id: 'downlink-plan', title: '합성 촬영 데이터를 예약에 할당 (다운링크 시작 전)', needs: (c) => (c.downlink && c.image && c.booking ? null : '전달 후'),
      run: async (c, key) => {
        const r = await actionsApi.downlinkPlan(c.booking!.id, { scenarioId: c.scenarioId!, loadId: c.downlink!.id, commandId: c.downlink!.body.load.commands[0].id.value, imagingCommandId: c.image!.body.load.commands[0].id.value }, key)
        return { patch: { planId: r.id }, shown: r }
      },
    },
    {
      id: 'manifest', title: '수신 manifest 생성', needs: (c) => (c.planId ? null : '할당 후'),
      run: async (c, key) => {
        const r = await actionsApi.manifest(c.scenarioId!, [c.planId!], key)
        return { patch: { manifestId: r.id }, shown: r }
      },
    },
    {
      id: 'link-downlink', title: '시뮬레이션 링크 연결 (다운링크 명령)', admin: true, needs: (c) => (c.downlink && c.scenarioId ? null : '전달 후'),
      run: (c, key) => setLink(c, c.downlink!.id, true, key),
    },
    {
      id: 'advance-downlink', title: '가상 시계를 다운링크 완료까지 진행', admin: true, needs: (c) => (c.downlink && c.scenarioId ? null : '전달 후'),
      run: async (c, key) => ({ shown: await advanceThrough(c, c.downlink!, downlinkCatalog(c), key) }),
    },
    {
      id: 'receive-bytes', title: '합성 바이트 지상 수신 시도', repeatable: true, needs: (c) => (c.planId ? null : '할당 후'),
      run: async (c, key) => ({ shown: await actionsApi.downlinkReceive(c.planId!, key) }),
    },
    {
      id: 'receive-downlink', title: '다운링크 명령 수신 시도 (RECONCILIATION)', repeatable: true, needs: (c) => (c.downlink ? null : '전달 후'),
      run: async (c, key) => ({ shown: await actionsApi.reception(c.downlink!.id, 'RECONCILIATION', key) }),
    },
    {
      id: 'reconcile-downlink', title: '다운링크 실행 근거 대조', repeatable: true, needs: (c) => (c.downlink ? null : '수신 후'),
      run: async (c, key) => {
        await actionsApi.evidence(c.scenarioId!, c.downlink!.id)
        return { shown: await actionsApi.reconcile(c.downlink!.id, key) }
      },
    },
    {
      id: 'product', title: 'manifest 완료 확인 후 제품·미리보기', repeatable: true, needs: (c) => (c.manifestId ? null : 'manifest 생성 후'),
      run: async (c, key) => {
        const m = await actionsApi.readManifest(c.manifestId!)
        if (m.body.completeness !== 'COMPLETE') throw new Error(`manifest가 아직 ${m.body.completeness}입니다. 잠시 후 다시 시도하세요.`)
        const p = await actionsApi.product(c.manifestId!)
        await actionsApi.preview(p.id, c.planId!, key)
        return { patch: { productId: p.id }, shown: { productId: p.id, completeness: m.body.completeness } }
      },
    },
    {
      id: 'result', title: '합성 결과 등록 (요청 완료)', needs: (c) => (c.productId && c.image && c.downlink ? null : '제품 확인 후'),
      run: async (c, key) => {
        const req = await actionsApi.request(requestId)
        return { shown: await actionsApi.registerResult(requestId, { expectedVersion: req.version, productId: c.productId!, imageLoadId: c.image!.id, downlinkLoadId: c.downlink!.id, reviewReference: review }, key) }
      },
    },
  ]
}

