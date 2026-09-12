import { request } from './client'
import type { AnomalyState, IncidentView, RecoveryResult, SafetyCheck, SafetyLatchState } from './types'

const ANOMALY = '/fe-api/anomaly'

export type OperatorActor = 'operator1' | 'operator2'

// The dev proxy has no login UI; this header tells it which local operator identity (both map
// to real, distinct accounts) should authenticate the request, so two-person recovery approval
// can be exercised locally. It is stripped by the proxy and never forwarded upstream.
function actorHeaders(actor: OperatorActor): HeadersInit {
  return actor === 'operator2' ? { 'X-Msc-Actor': 'operator2' } : {}
}

export const anomalyApi = {
  latch: (craft: string) => request<SafetyLatchState>(`${ANOMALY}/api/safety/${encodeURIComponent(craft)}`),

  history: (craft: string) =>
    request<Array<{ id: string; version: number; body: unknown }>>(
      `${ANOMALY}/api/safety/${encodeURIComponent(craft)}/history`,
    ),

  check: (craft: string, actor: OperatorActor) =>
    request<SafetyCheck>(`${ANOMALY}/api/safety/${encodeURIComponent(craft)}/check`, {
      method: 'POST',
      headers: actorHeaders(actor),
    }),

  incidents: (limit = 100) => request<AnomalyState[]>(`${ANOMALY}/api/anomalies?limit=${limit}`),

  incident: (id: string) => request<IncidentView>(`${ANOMALY}/api/anomalies/${encodeURIComponent(id)}`),

  requestRecovery: (
    craft: string,
    expectedSafetyVersion: number,
    decisionReference: string,
    idempotencyKey: string,
    actor: OperatorActor,
  ) =>
    request<RecoveryResult>(`${ANOMALY}/api/safety/${encodeURIComponent(craft)}/recovery-approvals`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...actorHeaders(actor),
      },
      body: JSON.stringify({ expectedSafetyVersion, decisionReference }),
    }),
}
