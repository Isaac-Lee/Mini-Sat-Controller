import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RequestPanel } from './RequestPanel'

afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, statusText: 'x', json: async () => body }
}

const option = { id: 'daejeon-a:1', west: 127.1, south: 36.2, east: 127.5, north: 36.5, sourceReference: 'gazetteer:daejeon-a:1' }

function requestState(status: string, extra: Record<string, unknown> = {}) {
  return {
    id: 'req-1',
    version: 4,
    body: {
      request: { id: { value: 'req-1' }, resolvedAoi: null, revision: 2, fulfillmentCriteria: '{}', deadline: null, requestedPriority: 30, interactionPreference: 'AUTO', status },
      owner: 'requester',
      target: 'Daejeon',
      area: null,
      criteria: { minimumCoverageFraction: 1, maximumCloudFraction: 1 },
      createdAt: { seconds: 1, nanos: 0, scale: 'TAI' },
      updatedAt: { seconds: 2, nanos: 0, scale: 'TAI' },
      reason: '',
      clarificationOptions: [],
      ...extra,
    },
  }
}

const original = {
  target: 'Daejeon',
  area: null,
  criteria: { minimumCoverageFraction: 0.8, maximumCloudFraction: 0.2 },
  deadline: { seconds: 9999999999, nanos: 0, scale: 'TAI' },
  priority: 30,
  preference: 'ASSISTED',
}

function stubTasking(state: ReturnType<typeof requestState>, reviseResponse = jsonResponse({})) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url.includes('/submissions/2')) return Promise.resolve(jsonResponse(original))
    if (init?.method === 'PUT') return Promise.resolve(reviseResponse)
    if (url.includes('/api/places')) return Promise.resolve(jsonResponse([]))
    return Promise.resolve(jsonResponse({ items: [state], nextCursor: null }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderPanel() {
  render(<RequestPanel offset={null} onSelectRequest={() => {}} selectedRequest={null} onOpenWorkflow={() => {}} />)
}

async function openRequest() {
  fireEvent.click(await screen.findByRole('button', { name: /Daejeon/ }))
}

function sentRevision(fetchMock: ReturnType<typeof stubTasking>) {
  const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
  expect(put).toBeDefined()
  return { url: put![0], body: JSON.parse(put![1]!.body as string) }
}

describe('RequestPanel revision', () => {
  it('resubmits the stored submission unchanged apart from target and priority', async () => {
    const fetchMock = stubTasking(requestState('ACCEPTED'))
    renderPanel()
    await openRequest()
    fireEvent.change(screen.getByLabelText('목표'), { target: { value: 'Daejeon station' } })
    fireEvent.change(screen.getByLabelText('우선순위 (0-100)', { selector: '#rev-priority' }), { target: { value: '70' } })
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/수정본 제출됨/)
    const { url, body } = sentRevision(fetchMock)
    expect(url).toContain('/api/requests/req-1')
    expect(body).toEqual({ expectedVersion: 4, submission: { ...original, target: 'Daejeon station', priority: 70 } })
  })

  it('drops a stored area when the target changes so the new target is resolved again', async () => {
    const withArea = { ...original, area: option }
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('/submissions/2')) return Promise.resolve(jsonResponse(withArea))
      if (init?.method === 'PUT') return Promise.resolve(jsonResponse({}))
      return Promise.resolve(jsonResponse({ items: [requestState('ACCEPTED', { area: option })], nextCursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    await openRequest()
    fireEvent.change(screen.getByLabelText('목표'), { target: { value: 'Busan' } })
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/수정본 제출됨/)
    expect(sentRevision(fetchMock).body.submission).toEqual({ ...withArea, area: null, target: 'Busan' })
  })

  it('keeps a stored area when only the priority changes', async () => {
    const withArea = { ...original, area: option }
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('/submissions/2')) return Promise.resolve(jsonResponse(withArea))
      if (init?.method === 'PUT') return Promise.resolve(jsonResponse({}))
      return Promise.resolve(jsonResponse({ items: [requestState('ACCEPTED', { area: option })], nextCursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    await openRequest()
    fireEvent.change(screen.getByLabelText('우선순위 (0-100)', { selector: '#rev-priority' }), { target: { value: '90' } })
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/수정본 제출됨/)
    expect(sentRevision(fetchMock).body.submission).toEqual({ ...withArea, priority: 90 })
  })

  it('sends the server clarification option object exactly as received', async () => {
    const fetchMock = stubTasking(requestState('CLARIFICATION_NEEDED', { clarificationOptions: [option] }))
    renderPanel()
    await openRequest()
    fireEvent.click(screen.getByRole('button', { name: '이 영역으로 확정' }))
    await screen.findByText(/수정본 제출됨/)
    expect(sentRevision(fetchMock).body.submission).toEqual({ ...original, area: option })
  })

  it('requires acknowledging that an existing schedule is dropped before revising', async () => {
    const fetchMock = stubTasking(requestState('SCHEDULED'))
    renderPanel()
    await openRequest()
    const submit = screen.getByRole('button', { name: /수정본 제출/ })
    expect(submit).toBeDisabled()
    fireEvent.click(screen.getByLabelText(/계획·일정이 무효화됨/))
    fireEvent.click(submit)
    await screen.findByText(/수정본 제출됨/)
    expect(sentRevision(fetchMock).body.expectedVersion).toBe(4)
  })

  it('reports a version conflict and keeps the edited input', async () => {
    stubTasking(requestState('ACCEPTED'), jsonResponse({ message: 'Request changed' }, 409))
    renderPanel()
    await openRequest()
    fireEvent.change(screen.getByLabelText('목표'), { target: { value: 'edited' } })
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/충돌/)
    expect(screen.getByLabelText('목표')).toHaveValue('edited')
  })

  it('explains an expired original deadline instead of dropping it', async () => {
    stubTasking(requestState('ACCEPTED'), jsonResponse({ message: 'Deadline must be in the future' }, 400))
    renderPanel()
    await openRequest()
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/마감시각이 이미 지났습니다/)
  })

  it('offers no revision for terminal requests', async () => {
    stubTasking(requestState('FULFILLED'))
    renderPanel()
    await openRequest()
    expect(screen.queryByRole('button', { name: /수정본 제출/ })).not.toBeInTheDocument()
  })
})

describe('RequestPanel approved place check', () => {
  async function check(places: unknown[]) {
    const fetchMock = vi.fn((url: string) =>
      Promise.resolve(jsonResponse(url.includes('/api/places') ? places : { items: [], nextCursor: null })),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    fireEvent.change(screen.getByLabelText('목표 이름 또는 좌표 설명'), { target: { value: '  Daejeon ' } })
    fireEvent.click(screen.getByRole('button', { name: '승인 지명 확인' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.includes('/api/places'))).toBe(true))
    return fetchMock
  }

  const place = (id: string) => ({ id, version: 1, aliases: ['daejeon'], area: option, approvalReference: 'adr-1' })

  it('queries the exact trimmed target and reports no approved place', async () => {
    const fetchMock = await check([])
    await screen.findByText(/승인 지명 없음/)
    expect(fetchMock.mock.calls.find(([url]) => url.includes('/api/places'))![0]).toContain('/api/places?target=Daejeon')
  })

  it('reports a single resolvable place with its approval reference', async () => {
    await check([place('daejeon-a')])
    await screen.findByText(/하나로 확정 가능/)
    expect(screen.getByText('daejeon-a:1')).toBeInTheDocument()
  })

  it('reports ambiguity, and the 101-row limit as over 100', async () => {
    await check(Array.from({ length: 101 }, (_, i) => place(`p${i}`)))
    await screen.findByText(/100곳 초과/)
  })

  it('clears the result when the target text changes', async () => {
    await check([])
    await screen.findByText(/승인 지명 없음/)
    fireEvent.change(screen.getByLabelText('목표 이름 또는 좌표 설명'), { target: { value: 'Seoul' } })
    expect(screen.queryByText(/승인 지명 없음/)).not.toBeInTheDocument()
  })

  it('discards a late response for a target that was edited away', async () => {
    let finishOld!: (value: ReturnType<typeof jsonResponse>) => void
    const old = new Promise<ReturnType<typeof jsonResponse>>((resolve) => { finishOld = resolve })
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('target=Old')) return old
      if (url.includes('/api/places')) return Promise.resolve(jsonResponse([place('new-place')]))
      return Promise.resolve(jsonResponse({ items: [], nextCursor: null }))
    }))
    renderPanel()
    const input = screen.getByLabelText('목표 이름 또는 좌표 설명')
    fireEvent.change(input, { target: { value: 'Old' } })
    fireEvent.click(screen.getByRole('button', { name: '승인 지명 확인' }))
    fireEvent.change(input, { target: { value: 'New' } })
    fireEvent.click(screen.getByRole('button', { name: '승인 지명 확인' }))
    await screen.findByText('new-place:1')
    await act(async () => { finishOld(jsonResponse([])) })
    expect(screen.queryByText(/승인 지명 없음/)).not.toBeInTheDocument()
    expect(screen.getByText('new-place:1')).toBeInTheDocument()
  })
})

describe('RequestPanel revision retry', () => {
  it('reuses the idempotency key when the same revision is retried after a network failure', async () => {
    let puts = 0
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('/submissions/2')) return Promise.resolve(jsonResponse(original))
      if (init?.method === 'PUT') return ++puts === 1 ? Promise.reject(new TypeError('offline')) : Promise.resolve(jsonResponse({}))
      return Promise.resolve(jsonResponse({ items: [requestState('ACCEPTED')], nextCursor: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    await openRequest()
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/수정 실패: \[connection\]/)
    fireEvent.click(screen.getByRole('button', { name: /수정본 제출/ }))
    await screen.findByText(/수정본 제출됨/)
    const keys = fetchMock.mock.calls
      .filter(([, init]) => init?.method === 'PUT')
      .map(([, init]) => (init!.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })
})
