import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

function mockResponse(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'status',
    json: async () => body ?? {},
  } as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request/ApiError kind mapping', () => {
  it('maps HTTP status codes to the ApiError kind the UI branches on', async () => {
    const cases: Array<[number, ApiError['kind']]> = [
      [401, 'auth'],
      [403, 'auth'],
      [404, 'not_found'],
      [409, 'conflict'],
      [400, 'invalid'],
      [500, 'connection'],
    ]
    for (const [status, kind] of cases) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse(status)))
      await expect(request('/x')).rejects.toMatchObject({ kind })
    }
  })

  it('surfaces a network failure as a connection-kind error, not a silent fallback', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('fetch failed')),
    )
    await expect(request('/x')).rejects.toMatchObject({ kind: 'connection' })
  })

  it('passes through the server message when present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse(409, { message: 'Request changed' })))
    await expect(request('/x')).rejects.toMatchObject({ kind: 'conflict', message: 'Request changed' })
  })
})
