import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadPlanning, loadExecution, loadRunEvidence } from './workflow'
import { resolveRole } from '../../devProxy'
afterEach(() => vi.unstubAllGlobals())
const ok = (value: unknown) => ({ ok: true, status: 200, json: async () => value })
describe('workflow provenance and partial failures', () => {
  it('rejects a run belonging to a different request and retains per-run failures', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(ok({ last_attempt_id: 'attempt' }))
      .mockResolvedValueOnce(ok({ runIds: ['valid', 'wrong', 'missing'] }))
      .mockResolvedValueOnce(ok({ id: 'valid', requestId: 'request' }))
      .mockResolvedValueOnce(ok({ id: 'wrong', requestId: 'other' }))
      .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) }))
    const data = await loadPlanning('request')
    expect(data.runs.map((r) => r.id)).toEqual(['valid'])
    expect(data.failures).toHaveLength(2)
  })
  it('makes no command reads when the result belongs to another request', async () => {
    const fetch = vi.fn().mockResolvedValue(ok({ body: { requestId: 'other', environment: 'SIMULATION' } }))
    vi.stubGlobal('fetch', fetch)
    await expect(loadExecution('request')).rejects.toThrow('identity')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('exposes command errors independently instead of claiming overall completion', async () => {
    const fetch = vi.fn().mockImplementation((path: string) => {
      if (path.endsWith('simulation-result')) return Promise.resolve(ok({ body: { requestId: 'request', environment: 'SIMULATION', imageLoadId: 'image', downlinkLoadId: 'downlink', productId: 'product' } }))
      if (path.endsWith('simulation-delivery')) return Promise.resolve({ ok: false, status: 503, json: async () => ({ message: 'unavailable' }) })
      return Promise.resolve(ok({ body: { status: 'saved' } }))
    })
    vi.stubGlobal('fetch', fetch)
    const cards = await loadExecution('request')
    expect(cards).toHaveLength(7)
    expect(cards.filter((card) => card.error)).toHaveLength(2)
    expect(cards.filter((card) => card.data)).toHaveLength(5)
  })
  it('uses the camera version returned by the work record, not a guessed latest version', async () => {
    const fetch = vi.fn().mockImplementation((path: string) => Promise.resolve(ok(
      path.endsWith('camera-work') ? { camera_model_version: 7 } : { scope: 'saved' },
    )))
    vi.stubGlobal('fetch', fetch)
    const cards = await loadRunEvidence('run')
    expect(fetch).toHaveBeenCalledWith('/fe-api/planning/api/planning/runs/run/camera/7', undefined)
    expect(cards).toHaveLength(4)
    expect(resolveRole('/fe-api/planning', 'GET', '/api/planning/runs/run/camera/7')).toBe('operator1')
    expect(resolveRole('/fe-api/planning', 'POST', '/api/planning/runs/run/camera/7')).toBe('requester')
  })
  it('grants read-only workflow paths operator access without granting new mutations', () => {
    expect(resolveRole('/fe-api/control', 'GET', '/api/command-loads/image/simulation-release')).toBe('operator1')
    expect(resolveRole('/fe-api/control', 'POST', '/api/command-loads/image/simulation-release')).toBe('requester')
    expect(resolveRole('/fe-api/product', 'GET', '/api/products/simulation-source-packages/product')).toBe('operator1')
    expect(resolveRole('/fe-api/planning', 'GET', '/api/planning/input-attempts/attempt/runs')).toBe('operator1')
    expect(resolveRole('/fe-api/planning', 'POST', '/api/planning/runs/run/simulation-commit')).toBe('requester')
  })
})
