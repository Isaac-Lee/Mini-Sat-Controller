import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request, setDemoBackend } from '../api/client'
import { anomalyApi } from '../api/anomaly'
import { taskingApi } from '../api/tasking'
import { loadExecution, loadPlanning } from '../api/workflow'
import { resetDemoBackend } from './backend'

const submission = (target: string) => ({ target, area: null, criteria: null, deadline: null, priority: 50, preference: 'AUTO' as const })

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  setDemoBackend(true)
  resetDemoBackend()
})
afterEach(() => {
  setDemoBackend(false)
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('demo backend', () => {
  it('answers without touching the network and seeds a complete example', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const page = await taskingApi.list()
    const seed = page.items.find((item) => item.body.request.status === 'FULFILLED')!
    const planning = await loadPlanning(seed.id)
    const execution = await loadExecution(seed.id)
    expect(planning.runs[0].run.candidates).toHaveLength(3)
    expect(execution.filter((card) => card.error)).toEqual([])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('advances a new request through the lifecycle over time', async () => {
    const { id } = (await taskingApi.create(submission('대전'), 'k')) as { id: string }
    const status = async () => (await taskingApi.get(id)).body.request.status
    expect(await status()).toBe('RECEIVED')
    vi.setSystemTime(Date.now() + 5_000)
    expect(await status()).toBe('ACCEPTED')
    vi.setSystemTime(Date.now() + 8_000)
    expect(await status()).toBe('SCHEDULED')
    vi.setSystemTime(Date.now() + 8_000)
    expect(await status()).toBe('FULFILLED')
  })

  it('asks for clarification when a place name is ambiguous', async () => {
    const { id } = (await taskingApi.create(submission('중구'), 'k')) as { id: string }
    vi.setSystemTime(Date.now() + 5_000)
    const state = await taskingApi.get(id)
    expect(state.body.request.status).toBe('CLARIFICATION_NEEDED')
    expect(state.body.clarificationOptions).toHaveLength(2)
  })

  it('rejects a stale version and needs two distinct operators to release the latch', async () => {
    const craft = 'sim-spaceeye-t1'
    const first = await anomalyApi.requestRecovery(craft, 1, 'r', 'k1', 'operator1')
    expect(first.state?.body.frozen).toBe(true)
    await expect(anomalyApi.requestRecovery(craft, 1, 'r', 'k2', 'operator2')).rejects.toMatchObject({ kind: 'conflict' })
    const same = await anomalyApi.requestRecovery(craft, 2, 'r', 'k3', 'operator1')
    expect(same.state?.version).toBe(2)
    const second = await anomalyApi.requestRecovery(craft, 2, 'r', 'k4', 'operator2')
    expect(second.state?.body.frozen).toBe(false)
  })

  it('reports unknown routes as not found instead of inventing data', async () => {
    await expect(request('/fe-api/tasking/api/unknown')).rejects.toBeInstanceOf(ApiError)
  })
})
