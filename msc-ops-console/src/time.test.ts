import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useTaiUtcOffset } from './time'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useTaiUtcOffset', () => {
  it('fetches an offset', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => ({ conversions: [{ tai: { seconds: 1000, nanos: 0, scale: 'TAI' }, utc: 'x' }], referenceArchiveSha256: 'x' }),
      }),
    )
    const { result } = renderHook(() => useTaiUtcOffset())
    await waitFor(() => expect(result.current.offset).not.toBeNull())
    // Public batch endpoint, never the SERVICE-only /internal path.
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/fe-api/flight/api/time/utc-to-tai')
  })
})

describe('exact UTC display helpers', () => {
  it('never renders a local time for a leap second', async () => {
    const { localOfServerUtc, formatServerUtc } = await import('./time')
    expect(localOfServerUtc('2016-12-31T23:59:60.000')).toBeNull()
    expect(localOfServerUtc('2017-01-01T00:00:00.000')).not.toBeNull()
    expect(formatServerUtc('2016-12-31T23:59:60.000')).toBe('2016-12-31 23:59:60.000 UTC')
  })

  it('batches concurrent conversions into one request and caches answers', async () => {
    const { exactUtc } = await import('./time')
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      const { instants } = JSON.parse(String(init?.body)) as { instants: Array<{ seconds: number; nanos: number }> }
      return { ok: true, status: 200, statusText: 'OK', json: async () => ({ conversions: instants.map((i) => ({ tai: i, utc: `u${i.seconds}` })), referenceArchiveSha256: 'x' }) }
    })
    vi.stubGlobal('fetch', fetchMock)
    const a = { seconds: 9001, nanos: 0, scale: 'TAI' as const }
    const b = { seconds: 9002, nanos: 0, scale: 'TAI' as const }
    expect(await Promise.all([exactUtc(a), exactUtc(b), exactUtc(a)])).toEqual(['u9001', 'u9002', 'u9001'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/fe-api/flight/api/time/tai-to-utc')
    await exactUtc(b)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })
})
