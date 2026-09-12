import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthorityPanel } from './AuthorityPanel'

afterEach(() => {
  vi.unstubAllGlobals()
})

const latchBody = {
  spacecraftId: 'sim-spaceeye-t1',
  policyVersion: 1,
  generation: 3,
  frozen: true,
  reasons: ['LOW_BATTERY'],
  approvals: [],
}

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, statusText: 'x', json: async () => body }
}

describe('AuthorityPanel approval semantics', () => {
  it('never calls fetch in demo mode', () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    render(<AuthorityPanel demoMode={true} offset={null} />)
    expect(screen.getByText(/데모 모드/)).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('approved:true with frozen:true is shown as "still frozen, one approval recorded", never as "approval complete"', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/safety/sim-spaceeye-t1/history')) return Promise.resolve(jsonResponse([]))
      if (url.includes('/api/anomalies')) return Promise.resolve(jsonResponse([]))
      if (url.endsWith('/recovery-approvals')) {
        return Promise.resolve(
          jsonResponse({
            approved: true,
            state: { id: 'sim-spaceeye-t1', version: 2, body: { ...latchBody, frozen: true, approvals: [{ actor: 'operator1' }] } },
          }),
        )
      }
      return Promise.resolve(jsonResponse({ id: 'sim-spaceeye-t1', version: 1, body: latchBody }))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AuthorityPanel demoMode={false} offset={null} />)
    await waitFor(() => expect(screen.getByText(/동결됨/)).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/결정 근거/), { target: { value: 'operator judgment' } })
    fireEvent.click(screen.getByText(/복구 승인 제출/))

    await waitFor(() =>
      expect(screen.getByText(/승인 1건 기록됨/)).toBeInTheDocument(),
    )
    expect(screen.queryByText(/동결이 해제되었습니다/)).not.toBeInTheDocument()
  })

  it('approved:false is shown as a denial with the blocking reasons, never as success', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/history')) return Promise.resolve(jsonResponse([]))
      if (url.includes('/api/anomalies')) return Promise.resolve(jsonResponse([]))
      if (url.endsWith('/recovery-approvals')) {
        return Promise.resolve(
          jsonResponse({
            approved: false,
            check: { clear: false, currentReasons: ['LOW_BATTERY'], evaluatedAt: { seconds: 1, nanos: 0, scale: 'TAI' } },
          }),
        )
      }
      return Promise.resolve(jsonResponse({ id: 'sim-spaceeye-t1', version: 1, body: latchBody }))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AuthorityPanel demoMode={false} offset={null} />)
    await waitFor(() => expect(screen.getByText(/동결됨/)).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/결정 근거/), { target: { value: 'operator judgment' } })
    fireEvent.click(screen.getByText(/복구 승인 제출/))

    await waitFor(() => expect(screen.getByText(/거부: 현재 안전 사유가 남아있습니다/)).toBeInTheDocument())
  })
})


describe('AuthorityPanel spacecraft binding', () => {
  it('clears approval state when switching spacecraft and submits only the newly loaded identity', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/anomalies')) return Promise.resolve(jsonResponse([]))
      const id = url.includes('/craft-b') ? 'craft-b' : 'sim-spaceeye-t1'
      if (url.endsWith('/recovery-approvals')) return Promise.resolve(jsonResponse({ approved: false }))
      return Promise.resolve(jsonResponse({ id, version: 1, body: { ...latchBody, spacecraftId: id } }))
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AuthorityPanel demoMode={false} offset={null} />)
    await screen.findByText(/동결됨/)
    fireEvent.change(screen.getByLabelText(/결정 근거/), { target: { value: 'craft A decision' } })
    fireEvent.change(screen.getByLabelText('위성 ID'), { target: { value: 'craft-b' } })
    expect(screen.queryByText(/복구 승인 제출/)).not.toBeInTheDocument()
    expect(screen.queryByText(/동결됨/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '조회' }))
    await screen.findByText(/동결됨/)
    expect(screen.getByLabelText(/결정 근거/)).toHaveValue('')
    fireEvent.change(screen.getByLabelText(/결정 근거/), { target: { value: 'craft B decision' } })
    fireEvent.click(screen.getByText(/복구 승인 제출/))
    await screen.findByText(/거부:/)
    const mutations = fetchMock.mock.calls.filter(([url]) => url.endsWith('/recovery-approvals'))
    expect(mutations).toHaveLength(1)
    expect(mutations[0][0]).toContain('/craft-b/recovery-approvals')
  })

  it('discards an old spacecraft response arriving after a new spacecraft was loaded', async () => {
    let finishOld!: (value: ReturnType<typeof jsonResponse>) => void
    const old = new Promise<ReturnType<typeof jsonResponse>>(resolve => { finishOld = resolve })
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/api/anomalies')) return Promise.resolve(jsonResponse([]))
      if (url.includes('/craft-b')) return Promise.resolve(jsonResponse({ id: 'craft-b', version: 9,
        body: { ...latchBody, spacecraftId: 'craft-b', reasons: ['CRAFT_B_REASON'] } }))
      return old
    }))
    render(<AuthorityPanel demoMode={false} offset={null} />)
    fireEvent.change(screen.getByLabelText('위성 ID'), { target: { value: 'craft-b' } })
    fireEvent.click(screen.getByRole('button', { name: '조회' }))
    await screen.findByText('CRAFT_B_REASON')
    await act(async () => { finishOld(jsonResponse({ id: 'sim-spaceeye-t1', version: 1, body: latchBody })) })
    expect(screen.getByText('CRAFT_B_REASON')).toBeInTheDocument()
    expect(screen.queryByText('LOW_BATTERY')).not.toBeInTheDocument()
    expect(screen.getByText(/expectedSafetyVersion=9/)).toBeInTheDocument()
  })
})
