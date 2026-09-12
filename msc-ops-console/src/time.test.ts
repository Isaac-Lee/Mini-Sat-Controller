import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useTaiUtcOffset } from './time'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useTaiUtcOffset demo/live boundary', () => {
  it('never calls fetch when disabled (demo mode) — no silent live fallback for time display', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const { result } = renderHook(() => useTaiUtcOffset(false))
    expect(result.current.offset).toBeNull()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('fetches an offset once enabled', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => ({ seconds: 1000, nanos: 0, scale: 'TAI' }),
      }),
    )
    const { result } = renderHook(() => useTaiUtcOffset(true))
    await waitFor(() => expect(result.current.offset).not.toBeNull())
  })
})
