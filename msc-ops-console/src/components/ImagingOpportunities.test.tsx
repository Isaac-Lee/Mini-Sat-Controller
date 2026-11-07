import { render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ImagingOpportunities, searchRanges } from './ImagingOpportunities'
import type { Area, OrbitSnapshot } from '../api/types'

afterEach(() => vi.unstubAllGlobals())
const DAY = 86_400_000
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
const instant = (seconds: number) => ({ seconds, nanos: 0, scale: 'TAI' as const })
const taiOf = (utc: string) => instant(Date.parse(utc + 'Z') / 1000 + 37)
const orbitWithEpoch = (id: string, epochMs: number) =>
  ({ id, elements: { epochUtc: new Date(epochMs).toISOString().slice(0, 19) } }) as OrbitSnapshot
// Each test uses its own AOI so the module-level cache never leaks between tests.
const areaAt = (west: number): Area => ({ id: `a${west}`, west, south: 36.2, east: west + 0.3, north: 36.5, sourceReference: 'gazetteer:a' })

describe('searchRanges', () => {
  it('splits a week into searches of at most 24 h, all within ±7 days of the epoch', () => {
    const now = Date.parse('2026-11-07T06:00:00Z')
    const epoch = now - 6 * 3_600_000
    const ranges = searchRanges(now, epoch)
    expect(ranges[0][0]).toBe(now)
    expect(ranges[ranges.length - 1][1]).toBe(epoch + 7 * DAY)
    for (const [s, e] of ranges) {
      expect(e - s).toBeGreaterThan(0)
      expect(e - s).toBeLessThanOrEqual(DAY)
      expect(Math.abs(s - epoch)).toBeLessThanOrEqual(7 * DAY)
      expect(Math.abs(e - epoch)).toBeLessThanOrEqual(7 * DAY)
    }
    ranges.slice(1).forEach(([s], i) => expect(s).toBe(ranges[i][1]))
  })

  it('returns nothing when the orbit epoch is more than 7 days old', () => {
    const now = Date.parse('2026-11-07T06:00:00Z')
    expect(searchRanges(now, now - 8 * DAY)).toEqual([])
  })
})

describe('ImagingOpportunities', () => {
  it('asks Flight Dynamics in ≤24 h pieces once, joins a pass split at a boundary, and reuses the result', async () => {
    const fetch = vi.fn((path: string, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body))
      if (path.endsWith('/api/time/utc-to-tai')) return Promise.resolve(ok({ conversions: body.utc.map((utc: string) => ({ utc, tai: taiOf(utc) })), referenceArchiveSha256: 'x' }))
      const { start, end } = body.horizon
      // The first range returns a window ending exactly at its end; the second one continues it.
      const first = fetch.mock.calls.filter(([p]) => p.endsWith('/access-predictions'))[0][1]
      const isFirst = JSON.parse(String(first?.body)).horizon.start.seconds === start.seconds
      const windows = isFirst ? [{ start: instant(end.seconds - 30), end }] : start.seconds === JSON.parse(String(first?.body)).horizon.end.seconds ? [{ start, end: instant(start.seconds + 20) }] : []
      return Promise.resolve(ok({ id: 'p', body: { model: 'OREKIT', windows } }))
    })
    vi.stubGlobal('fetch', fetch)
    const area = areaAt(127.25)
    const orbit = orbitWithEpoch('orbit-1', Date.now() - 6 * 3_600_000)
    const onShowPass = vi.fn()
    const props = { area, flightOrbit: orbit, onImportOrbit: () => {}, importBusy: false, onShowPass }
    const { unmount } = render(<StrictMode><ImagingOpportunities {...props} /></StrictMode>)
    ;(await screen.findByRole('button', { name: '지도에서 보기' })).click()

    const posts = fetch.mock.calls.filter(([p]) => p.endsWith('/access-predictions')).map(([, init]) => JSON.parse(String(init?.body)))
    expect(posts.length).toBe(7)
    for (const q of posts) {
      expect(q.horizon.end.seconds - q.horizon.start.seconds).toBeLessThanOrEqual(86_400)
      expect(q.target).toMatchObject({ latitudeDegrees: 36.35, longitudeDegrees: 127.4 })
    }
    expect(fetch.mock.calls.filter(([p]) => p.endsWith('/utc-to-tai'))).toHaveLength(1)
    const firstEnd = posts[0].horizon.end.seconds
    expect(onShowPass).toHaveBeenCalledWith({ start: instant(firstEnd - 30), end: instant(firstEnd + 20) })
    expect(screen.getAllByRole('button', { name: '지도에서 보기' })).toHaveLength(1)

    unmount()
    render(<ImagingOpportunities {...props} />)
    await screen.findByRole('button', { name: '지도에서 보기' })
    expect(fetch.mock.calls.filter(([p]) => p.endsWith('/access-predictions'))).toHaveLength(7)
  })

  it('says the orbit is too old instead of sending a request Flight Dynamics would reject', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const orbit = orbitWithEpoch('orbit-old', Date.now() - 8 * DAY)
    render(<ImagingOpportunities area={areaAt(126)} flightOrbit={orbit} onImportOrbit={() => {}} importBusy={false} onShowPass={() => {}} />)
    expect(await screen.findByText(/7일이 모델 유효 범위/)).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('offers an orbit import instead of computing when Flight Dynamics has no orbit', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const onImportOrbit = vi.fn()
    render(<ImagingOpportunities area={areaAt(125)} flightOrbit="missing" onImportOrbit={onImportOrbit} importBusy={false} onShowPass={() => {}} />)
    screen.getByRole('button', { name: '궤도 가져오기' }).click()
    await waitFor(() => expect(onImportOrbit).toHaveBeenCalled())
    expect(fetch).not.toHaveBeenCalled()
  })
})
