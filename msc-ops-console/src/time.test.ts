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
        json: async () => ({ seconds: 1000, nanos: 0, scale: 'TAI' }),
      }),
    )
    const { result } = renderHook(() => useTaiUtcOffset())
    await waitFor(() => expect(result.current.offset).not.toBeNull())
  })
})
