import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { anomalyApi } from '../api/anomaly'
import { api, ApiError, request, setDemoBackend } from '../api/client'
import { planningApi } from '../api/planning'
import { productsApi } from '../api/products'
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

  it('keeps access windows inside the requested horizon', async () => {
    const start = await api.utcToTai(new Date().toISOString().slice(0, 19))
    const end = { ...start, seconds: start.seconds + 86_400 }
    const target = { id: 't', latitudeDegrees: 0, longitudeDegrees: 127, altitudeMeters: 0 }
    const query = { kind: 'POINT_IMAGING' as const, target, horizon: { start, end }, minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 }
    const { body } = await api.requestAccessPrediction('demo-orbit-snapshot-1', query, 'k')
    expect(body.windows.every((w) => w.start.seconds >= start.seconds && w.end.seconds <= end.seconds)).toBe(true)
  })

  it('rejects searches Flight Dynamics would reject: over 24 h, or beyond 7 days from the epoch', async () => {
    const start = await api.utcToTai(new Date().toISOString().slice(0, 19))
    const target = { id: 't', latitudeDegrees: 36, longitudeDegrees: 127, altitudeMeters: 0 }
    const query = (s: number, e: number) => ({ kind: 'POINT_IMAGING' as const, target, horizon: { start: { ...start, seconds: s }, end: { ...start, seconds: e } }, minimumElevationDegrees: 10, maximumOffNadirDegrees: 30, minimumDurationSeconds: 1 })
    await expect(api.requestAccessPrediction('demo-orbit-snapshot-1', query(start.seconds, start.seconds + 86_401), 'k1')).rejects.toMatchObject({ kind: 'invalid' })
    await expect(api.requestAccessPrediction('demo-orbit-snapshot-1', query(start.seconds + 7 * 86_400, start.seconds + 7 * 86_400 + 3_600), 'k2')).rejects.toMatchObject({ kind: 'invalid' })
  })

  it('refuses a second cancel and keeps an unplanned cancelled request out of planning', async () => {
    const { id } = (await taskingApi.create(submission('대전'), 'k')) as { id: string }
    const state = await taskingApi.get(id)
    await taskingApi.cancel(id, state.version, 'c1')
    const cancelled = await taskingApi.get(id)
    await expect(taskingApi.cancel(id, cancelled.version, 'c2')).rejects.toMatchObject({ kind: 'conflict' })
    await expect(planningApi.intakeStatus(id)).rejects.toMatchObject({ kind: 'not_found' })
  })

  it('links product sources to local demo data instead of the live route', () => {
    expect(productsApi.sourceUrl('r', 's', true)).toMatch(/^data:image\//)
    expect(productsApi.sourceUrl('r', 's')).toMatch(/^data:/)
  })

  it('gives each evaluated candidate its own footprint row', async () => {
    const seed = (await taskingApi.list()).items.find((i) => i.body.request.status === 'FULFILLED')!
    const { runs } = await loadPlanning(seed.id)
    const camera = (await request(`/fe-api/planning/api/planning/runs/${runs[0].id}/camera/1`)) as { body: { candidates: Array<{ cameraResult: { id: string } }> } }
    const rows = await Promise.all(
      camera.body.candidates.map(async (c) => {
        const result = (await request(`/fe-api/flight/api/camera-footprint-evaluations/${c.cameraResult.id}/result`)) as { samples: Array<{ footprintCorners: Array<{ northMeters: number }> }> }
        return result.samples[0].footprintCorners[0].northMeters
      }),
    )
    // How many passes are sunlit depends on when the demo orbit was loaded, so this asserts distinctness only.
    expect(rows.length).toBeGreaterThanOrEqual(1)
    expect(new Set(rows).size).toBe(rows.length)
  })

  it('reports unknown routes as not found instead of inventing data', async () => {
    await expect(request('/fe-api/tasking/api/unknown')).rejects.toBeInstanceOf(ApiError)
  })
})
