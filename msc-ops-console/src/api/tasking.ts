import { request } from './client'
import type { RequestPage, RequestState, Submission } from './types'

const TASKING = '/fe-api/tasking'

export const taskingApi = {
  create: (submission: Submission, idempotencyKey: string) =>
    request<Record<string, unknown>>(`${TASKING}/api/requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(submission),
    }),

  get: (id: string) => request<RequestState>(`${TASKING}/api/requests/${id}`),

  list: (limit = 100, after = '') =>
    request<RequestPage>(`${TASKING}/api/requests?limit=${limit}&after=${encodeURIComponent(after)}`),

  submittedRevision: (id: string, revision: number) =>
    request<Submission>(`${TASKING}/api/requests/${id}/submissions/${revision}`),

  revise: (id: string, expectedVersion: number, submission: Submission, idempotencyKey: string) =>
    request<Record<string, unknown>>(`${TASKING}/api/requests/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ expectedVersion, submission }),
    }),

  cancel: (id: string, expectedVersion: number, idempotencyKey: string) =>
    request<Record<string, unknown>>(`${TASKING}/api/requests/${id}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ expectedVersion }),
    }),
}
