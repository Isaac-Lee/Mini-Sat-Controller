import { request } from './client'

const PLANNING = '/fe-api/planning'

// This client currently covers intake only. Planning also exposes run and simulation schedule APIs.
export interface PlanningWorkRow {
  request_id: string
  revision: number
  status: string
  attempts: number
  last_attempt_id: string | null
  next_attempt_at: string | null
}

export interface PlanningEvidence {
  service: string
  path: string
  sha256: string
  value: unknown
}

export interface PlanningAsset {
  spacecraftId: string
  inputs: Record<string, PlanningEvidence>
  missing: string[]
}

export interface PlanningAttempt {
  id: string
  requestId: string
  revision: number
  capturedAt: { seconds: number; nanos: number; scale: 'TAI' | 'UTC' }
  status: string
  request: PlanningEvidence | null
  assets: PlanningAsset[]
  issues: string[]
}

export const planningApi = {
  intakeStatus: (requestId: string) => request<PlanningWorkRow>(`${PLANNING}/api/planning/requests/${requestId}`),

  attempt: (attemptId: string) =>
    request<PlanningAttempt>(`${PLANNING}/api/planning/input-attempts/${attemptId}`),
}
