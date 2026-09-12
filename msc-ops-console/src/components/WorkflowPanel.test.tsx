import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkflowPanel } from './WorkflowPanel'
import type { RequestState } from '../api/types'
afterEach(() => vi.unstubAllGlobals())
const selected = (id: string) => ({ id, body: { target: id, request: { status: 'FULFILLED' } } }) as RequestState
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
describe('workflow visibility boundaries', () => {
  it('does not fetch in demo or without request selection', () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const { rerender } = render(<WorkflowPanel demoMode selectedRequest={selected('a')} offset={null} />)
    rerender(<WorkflowPanel demoMode={false} selectedRequest={null} offset={null} />)
    expect(fetch).not.toHaveBeenCalled()
  })
  it('ignores late planning responses after request changes', async () => {
    let resolve!: (data: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation((path: string) => {
      if (path.endsWith('/requests/a')) return new Promise((done) => { resolve = done })
      if (path.endsWith('/requests/b')) return Promise.resolve(ok({ status: 'NEW_REQUEST', last_attempt_id: null, attempts: 1 }))
      return Promise.resolve({ ok: false, status: 404, json: async () => ({}) })
    }))
    const { rerender } = render(<WorkflowPanel demoMode={false} selectedRequest={selected('a')} offset={null} />)
    rerender(<WorkflowPanel demoMode={false} selectedRequest={selected('b')} offset={null} />)
    await screen.findByText(/NEW_REQUEST/)
    await act(async () => { resolve(ok({ status: 'OLD_REQUEST', last_attempt_id: null })) })
    expect(screen.queryByText(/OLD_REQUEST/)).not.toBeInTheDocument()
  })
})
