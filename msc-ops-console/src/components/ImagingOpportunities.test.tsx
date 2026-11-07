import { render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ImagingOpportunities } from './ImagingOpportunities'
import type { OrbitSnapshot } from '../api/types'

afterEach(() => vi.unstubAllGlobals())
const area = { id: 'a', west: 127.25, south: 36.2, east: 127.55, north: 36.5, sourceReference: 'gazetteer:a' }
const orbit = { id: 'orbit-1' } as OrbitSnapshot
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
const instant = (seconds: number) => ({ seconds, nanos: 0, scale: 'TAI' as const })

describe('ImagingOpportunities', () => {
  it('asks Flight Dynamics for the AOI centre once per mount, even under StrictMode', async () => {
    const fetch = vi.fn((path: string, init?: RequestInit) => {
      if (path.endsWith('/utc-to-tai')) return Promise.resolve(ok(instant(Date.parse(JSON.parse(String(init?.body)).utc + 'Z') / 1000 + 37)))
      return Promise.resolve(ok({ id: 'p', body: { model: 'OREKIT', windows: [{ start: instant(2_000_000_000), end: instant(2_000_000_042) }] } }))
    })
    vi.stubGlobal('fetch', fetch)
    const onShowPass = vi.fn()
    render(<StrictMode><ImagingOpportunities area={area} flightOrbit={orbit} onImportOrbit={() => {}} importBusy={false} onShowPass={onShowPass} offset={null} /></StrictMode>)
    ;(await screen.findByRole('button', { name: '지도에서 보기' })).click()
    expect(onShowPass).toHaveBeenCalledWith({ start: instant(2_000_000_000), end: instant(2_000_000_042) })
    const posts = fetch.mock.calls.filter(([path]) => path.endsWith('/access-predictions'))
    const bodies = posts.map(([, init]) => JSON.parse(String(init?.body)))
    const keys = posts.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'))
    expect(new Set(keys).size).toBe(1)
    expect(new Set(bodies.map((b) => JSON.stringify(b))).size).toBe(1)
    expect(bodies[0].target).toMatchObject({ latitudeDegrees: 36.35, longitudeDegrees: 127.4 })
    expect(bodies[0].horizon.end.seconds - bodies[0].horizon.start.seconds).toBe(7 * 86_400)
  })

  it('offers an orbit import instead of computing when Flight Dynamics has no orbit', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const onImportOrbit = vi.fn()
    render(<ImagingOpportunities area={area} flightOrbit="missing" onImportOrbit={onImportOrbit} importBusy={false} onShowPass={() => {}} offset={null} />)
    screen.getByRole('button', { name: '궤도 가져오기' }).click()
    await waitFor(() => expect(onImportOrbit).toHaveBeenCalled())
    expect(fetch).not.toHaveBeenCalled()
  })
})
