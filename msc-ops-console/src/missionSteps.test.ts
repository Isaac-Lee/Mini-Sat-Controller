import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildSteps, type Ctx } from './missionSteps'

afterEach(() => vi.unstubAllGlobals())

const start = { seconds: 2_000_000_000, nanos: 0, scale: 'TAI' }
const run = {
  id: 'run-1',
  requestId: 'req-1',
  spacecraftId: 'sim-1',
  run: { candidates: [{ id: { value: 'cand-1' }, activity: { id: { value: 'act-img' }, window: { start, end: start } } }] },
  catalog: { value: { id: 'cat-img', version: 3, durationSeconds: 10 } },
  operations: { catalogs: { DOWNLINK: { value: { id: 'cat-dl', version: 4, durationSeconds: 20 } } } },
}
const load = (id: string, command: string) => ({ id, body: { load: { checksum: `sum-${id}`, commands: [{ id: { value: command }, timeTag: { ticks: 600 } }] } } })

function jsonResponse(body: unknown, status = 200) {
  return { ok: status < 300, status, statusText: 'x', json: async () => body }
}

function router(nowSeconds: number) {
  const calls: Array<{ url: string; body: unknown; key: string | null }> = []
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined, key: headers['Idempotency-Key'] ?? null })
    if (url.includes('time/utc-to-tai') || url.includes('/internal/time')) return jsonResponse({ seconds: nowSeconds, nanos: 0, scale: 'TAI' })
    if (url.endsWith('/simulation-commit')) return jsonResponse({ id: 'd1', body: { schedule: { key: 'K', version: 1 } } })
    if (url.endsWith('/simulation-time-correlations')) return jsonResponse({ version: 7 })
    if (url.endsWith('/command-loads/prepare')) return jsonResponse(load('L-img', 'cmd-img'))
    return jsonResponse({})
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}

const step = (id: string) => buildSteps('req-1', 'review', 'booking-1').find((s) => s.id === id)!
const base: Ctx = { links: {}, run: run as unknown as Ctx['run'], candidate: run.run.candidates[0], cameraModelVersion: 2 }

describe('mission steps derive bodies from earlier server responses', () => {
  it('commits the selected candidate with its camera model version and an idempotency key', async () => {
    const calls = router(0)
    const { patch } = await step('commit').run(base, 'k1')
    expect(calls[0].url).toBe('/fe-api/planning/api/planning/runs/run-1/simulation-commit')
    expect(calls[0].body).toEqual({ candidateId: 'cand-1', cameraModelVersion: 2, expectedScheduleVersion: 0, reviewReference: 'review' })
    expect(calls[0].key).toBe('k1')
    expect(patch?.schedule).toEqual({ key: 'K', version: 1 })
  })

  it('anchors the simulation clock 60 s and the approval deadline 30 s before the imaging window', async () => {
    const calls = router(0)
    const { patch } = await step('correlation').run({ ...base, schedule: { key: 'K', version: 1 } }, 'k')
    const body = calls[0].body as { correlation: { taiEpoch: { seconds: number }; environment: string } }
    expect(body.correlation.taiEpoch.seconds).toBe(start.seconds - 60)
    expect(body.correlation.environment).toBe('SIMULATION')
    expect(patch?.deadline?.seconds).toBe(start.seconds - 30)
  })

  it('prepares the load for the candidate activity and its pinned catalog', async () => {
    const calls = router(0)
    const c = { ...base, schedule: { key: 'K', version: 1 }, correlation: { version: 7, ticksPerSecond: 10 }, deadline: { ...start, seconds: start.seconds - 30 } }
    await step('prepare-image').run(c, 'k')
    expect(calls[0].body).toMatchObject({
      schedule: 'K',
      scheduleVersion: 1,
      correlationVersion: 7,
      catalogsByActivity: { 'act-img': { catalogId: 'cat-img', catalogVersion: 3 } },
      parametersByActivity: { 'act-img': {} },
      deadline: { seconds: start.seconds - 30 },
    })
  })

  it('refuses to approve once validUntil has passed, without calling approvals', async () => {
    const calls = router(start.seconds)
    const c = { ...base, image: load('L-img', 'cmd-img'), deadline: { ...start, seconds: start.seconds - 30 } }
    await expect(step('approve-image').run(c, 'k')).rejects.toThrow(/validUntil/)
    expect(calls.some((x) => x.url.endsWith('/approvals'))).toBe(false)
  })

  it('allocates the imaging command payload to the booking with the downlink command', async () => {
    const calls = router(0)
    const c = { ...base, scenarioId: 'S', image: load('L-img', 'cmd-img'), downlink: load('L-dl', 'cmd-dl'), booking: { id: 'booking-1', window: {} } }
    await step('downlink-plan').run(c, 'k')
    expect(calls[0].url).toBe('/fe-api/ground/api/bookings/booking-1/simulation-downlink-plan')
    expect(calls[0].body).toEqual({ scenarioId: 'S', loadId: 'L-dl', commandId: 'cmd-dl', imagingCommandId: 'cmd-img' })
  })

  it('advances the virtual clock to command start plus catalog duration in ticks', async () => {
    const calls = router(0)
    vi.mocked(fetch).mockImplementation(async (url: string | URL | Request) => {
      calls.push({ url: String(url), body: undefined, key: null })
      return jsonResponse(String(url).endsWith('/advance') ? {} : { version: 5, body: { currentTick: 0 } }) as unknown as Response
    })
    const c = { ...base, scenarioId: 'S', correlation: { version: 7, ticksPerSecond: 10 }, image: load('L-img', 'cmd-img') }
    await step('advance-image').run(c, 'k')
    const advance = vi.mocked(fetch).mock.calls.find(([u]) => String(u).endsWith('/advance'))!
    expect(JSON.parse(String(advance[1]!.body))).toEqual({ expectedVersion: 5, targetTick: 600 + 10 * 10 })
  })

  it('needs gates block steps until their inputs exist', () => {
    expect(step('commit').needs({ links: {} })).not.toBeNull()
    expect(step('result').needs({ links: {} })).not.toBeNull()
    expect(buildSteps('req-1', 'r', '').find((s) => s.id === 'booking')!.needs(base)).toMatch(/예약 ID/)
  })
})
