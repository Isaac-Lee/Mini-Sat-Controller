import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MonitorPanel } from './MonitorPanel'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MonitorPanel demo/live boundary', () => {
  it('never calls fetch in demo mode (no silent live fallback)', () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    render(<MonitorPanel demoMode={true} offset={null} />)
    expect(screen.getByText(/데모 모드/)).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('shows a distinct "binding not registered" message on 404, not a fabricated estimate', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => ({}),
      }),
    )
    render(<MonitorPanel demoMode={false} offset={null} />)
    await waitFor(() => expect(screen.getByText(/관측 바인딩 미등록/)).toBeInTheDocument())
  })

  it('renders the server confidence value (FRESH) with its label, not a hardcoded default', async () => {
    const body = {
      estimate: {
        id: 'sim-spaceeye-t1',
        version: 1,
        body: {
          binding: {
            spacecraftId: 'sim-spaceeye-t1',
            version: 1,
            source: 'simulator:spaceeye-scenario',
            environment: 'SIMULATION',
            maximumAgeSeconds: 60,
            futureSkewSeconds: 5,
            approvalReference: 'ref',
          },
          accepted: {
            frame: {
              id: 'f1',
              spacecraftId: 'sim-spaceeye-t1',
              bindingVersion: 1,
              source: 'simulator:spaceeye-scenario',
              sequence: 1,
              observedAt: { seconds: 100, nanos: 0, scale: 'TAI' },
              quality: 'GOOD',
              mode: 'NOMINAL',
              batteryWh: 10,
              storageMb: 20,
              propellantKg: 5,
              provenance: 'sim',
            },
            receivedAt: { seconds: 101, nanos: 0, scale: 'TAI' },
            disposition: 'ACCEPTED',
          },
          newestEvidence: null,
        },
      },
      evaluatedAt: { seconds: 102, nanos: 0, scale: 'TAI' },
      confidence: 'FRESH',
    }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, status: 200, statusText: 'OK', json: async () => body }),
    )
    render(<MonitorPanel demoMode={false} offset={null} />)
    await waitFor(() => expect(screen.getByText(/FRESH/)).toBeInTheDocument())
    expect(screen.getByText(/NOMINAL/)).toBeInTheDocument()
  })
})
