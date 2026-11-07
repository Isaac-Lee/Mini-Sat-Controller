import { request } from './client'
import type { MissionInstant } from './types'

// Operator/admin calls of the V1 SIMULATION flow (docs/backend/simulation-*-v1.md, ADR-0014).
// Every write carries an Idempotency-Key; callers keep one key per step attempt so a retry after a
// lost response is deduplicated by the backend instead of repeated.

type Json = Record<string, unknown>
const id = encodeURIComponent

function post<T = Json>(service: string, path: string, body: unknown, key?: string): Promise<T> {
  return request<T>(`/fe-api/${service}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}
const get = <T = Json>(service: string, path: string) => request<T>(`/fe-api/${service}${path}`)

export const seconds = (instant: MissionInstant, delta: number): MissionInstant => ({ ...instant, seconds: instant.seconds + delta })

export interface Prepared {
  id: string
  body: { load: { checksum: string; commands: Array<{ id: { value: string }; timeTag: { ticks: number } }> } }
}

export const actionsApi = {
  cameraWork: (run: string) => get<{ camera_model_version?: number }>('planning', `/api/planning/runs/${id(run)}/camera-work`),
  commit: (run: string, body: { candidateId: string; cameraModelVersion: number; expectedScheduleVersion: number; reviewReference: string }, key: string) =>
    post<{ id: string; body: { schedule: { key: unknown; version: number } } }>('planning', `/api/planning/runs/${id(run)}/simulation-commit`, body, key),
  commitDownlink: (run: string, body: { bookingId: string; window: unknown; expectedScheduleVersion: number; reviewReference: string }, key: string) =>
    post<{ id: string; body: { activityId: string; schedule: { key: unknown; version: number } } }>('planning', `/api/planning/runs/${id(run)}/simulation-downlink`, body, key),

  currentCorrelation: (craft: string) =>
    get<{ version: number; body: { ticksPerSecond: number; validInterval: { start: MissionInstant; end: MissionInstant } } }>('mission', `/api/simulation-time-correlations/${id(craft)}`),
  correlation: (body: Json, key: string) => post<{ version: number; body: { ticksPerSecond: number } }>('mission', '/api/simulation-time-correlations', body, key),
  authorityPolicy: (craft: string) => get<{ version: number; body: Json & { rules: Json[] } }>('mission', `/api/authority-policies/${id(craft)}`),
  publishAuthority: (body: Json, key: string) => post('mission', '/api/authority-policies', body, key),

  prepare: (body: Json, key: string) => post<Prepared>('control', '/api/command-loads/prepare', body, key),
  approve: (load: string, body: { checksum: string; validUntil: MissionInstant; expectedVersion: number }, key: string) =>
    post('control', `/api/command-loads/${id(load)}/approvals`, body, key),
  scheduleCheck: (load: string) => post<{ reasons: string[] }>('control', `/api/command-loads/${id(load)}/schedule-check`, undefined),
  release: (load: string, body: { scenarioId: string; checksum: string; reviewReference: string }, key: string) =>
    post('control', `/api/command-loads/${id(load)}/simulation-release`, body, key),
  dispatch: (load: string) => post<{ body: { status: string; reason: string } }>('control', `/api/command-loads/${id(load)}/simulation-dispatch`, undefined),
  reception: (load: string, channel: 'ACKNOWLEDGMENT' | 'RECONCILIATION', key: string) =>
    post<{ belief: string; reason: string }>('control', `/api/command-loads/${id(load)}/simulation-reception`, { channel }, key),
  evidence: (scenario: string, load: string) => get('control', `/api/simulation-execution-evidence/${id(scenario)}/${id(load)}`),
  reconcile: (load: string, key: string) => post<{ body: { status: string; requestIds: string[] } }>('control', `/api/command-loads/${id(load)}/simulation-execution/reconcile`, undefined, key),

  createScenario: (body: Json, key: string) => post<{ id: string; version: number }>('simulator', '/api/simulation/scenarios', body, key),
  scenario: (scenario: string) => get<{ version: number; body: { currentTick: number } }>('simulator', `/api/simulation/scenarios/${id(scenario)}`),
  advance: (scenario: string, body: { expectedVersion: number; targetTick: number }, key: string) =>
    post('simulator', `/api/simulation/scenarios/${id(scenario)}/advance`, body, key),
  link: (scenario: string, load: string, body: { expectedVersion: number; connected: boolean; acknowledgmentLost: boolean; notBeforeTick: number; provenance: string }, key: string) =>
    post('simulator', `/api/simulation/scenarios/${id(scenario)}/loads/${id(load)}/link`, body, key),

  booking: (booking: string) => get<{ status: string; request: { window: unknown; spacecraftId: string } }>('ground', `/api/bookings/${id(booking)}`),
  downlinkPlan: (booking: string, body: { scenarioId: string; loadId: string; commandId: string; imagingCommandId: string }, key: string) =>
    post<{ id: string }>('ground', `/api/bookings/${id(booking)}/simulation-downlink-plan`, body, key),
  downlinkReceive: (plan: string, key: string) =>
    post<{ belief: string; reason?: string }>('ground', `/api/simulation-downlinks/${id(plan)}/receive`, { channel: 'RECONCILIATION' }, key),
  manifest: (scenarioId: string, planIds: string[], key: string) =>
    post<{ id: string; body: { completeness: string } }>('acquisition', '/api/acquisition/simulation-manifests', { scenarioId, planIds }, key),
  readManifest: (manifest: string) => get<{ body: { completeness: string } }>('acquisition', `/api/acquisition/simulation-manifests/${id(manifest)}`),
  product: (manifest: string) => get<{ id: string }>('product', `/api/products/simulation-source-packages/${id(manifest)}`),
  preview: (product: string, plan: string, key: string) =>
    post('product', `/api/products/simulation-source-packages/${id(product)}/sources/${id(plan)}/preview`, {}, key),
  request: (requestId: string) => get<{ version: number; body: { request: { status: string } } }>('tasking', `/api/requests/${id(requestId)}`),
  registerResult: (requestId: string, body: { expectedVersion: number; productId: string; imageLoadId: string; downlinkLoadId: string; reviewReference: string }, key: string) =>
    post('tasking', `/api/requests/${id(requestId)}/simulation-result`, body, key),
}
