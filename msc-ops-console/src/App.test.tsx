import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { setDemoBackend } from './api/client'
import { resetDemoBackend } from './demo/backend'

afterEach(() => {
  window.history.replaceState({}, '', '/')
  setDemoBackend(false)
  resetDemoBackend()
  vi.unstubAllGlobals()
})

describe('App demo/live boundary', () => {
  it('serves the whole console from the fake backend under ?demo, without any network call', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    window.history.replaceState({}, '', '/?demo')
    render(<App />)
    screen.getByRole('tab', { name: 'Mission Map' }).click()
    expect(await screen.findByText('보존 GP에서 파생한 TLE 다운로드')).toHaveAttribute('href', expect.stringMatching(/^data:/))
    await screen.findByText(/DEMO \(CelesTrak/)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('uses only the real API without ?demo', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, status: 503, statusText: 'x', json: async () => ({}) })
    vi.stubGlobal('fetch', fetch)
    render(<App />)
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(fetch.mock.calls.every(([path]) => String(path).startsWith('/fe-api/'))).toBe(true)
    expect(screen.queryByText(/데모 모드 —/)).not.toBeInTheDocument()
  })
})
