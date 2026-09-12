import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ProductPanel } from './ProductPanel'

const result = (requestId = 'request-1') => ({
  id: requestId, version: 1,
  body: {
    requestId, requestRevision: 2, environment: 'SIMULATION', status: 'COMPLETE',
    completionBasis: 'synthetic workflow; physical image quality not assessed',
    productId: 'product-1', scenarioId: 'scenario-1', imageLoadId: 'image-1', downlinkLoadId: 'downlink-1',
    reviewer: 'operator1', reviewReference: 'review-1', completedAt: { seconds: 100, nanos: 0, scale: 'TAI' },
    sourceHashes: { execution: 'abc123' },
    sources: [{ receiptId: 'receipt/1', payloadId: 'payload-1', byteCount: 1000, sha256: 'raw-hash',
      previewByteCount: 100, previewSha256: 'png-hash', contentPath: 'https://untrusted.invalid/raw', previewPath: '//untrusted.invalid/preview' }],
  },
})
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
afterEach(() => vi.unstubAllGlobals())

describe('request-owned synthetic results', () => {
  it('makes no live request for demo mode or no selection', () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const { rerender } = render(<ProductPanel demoMode requestId="request-1" offset={null} />)
    rerender(<ProductPanel demoMode={false} requestId={null} offset={null} />)
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('shows server provenance and uses only request-owned, encoded download routes', async () => {
    const fetch = vi.fn().mockResolvedValue(ok(result())); vi.stubGlobal('fetch', fetch)
    render(<ProductPanel demoMode={false} requestId="request-1" offset={null} />)
    expect(await screen.findByText('SIMULATION · COMPLETE')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/fe-api/tasking/api/requests/request-1/simulation-result', undefined)
    expect(screen.getByText('raw-hash')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '합성 원본 다운로드' })).toHaveAttribute('href', '/fe-api/tasking/api/requests/request-1/simulation-result/sources/receipt%2F1/content')
    expect(screen.getByRole('img')).toHaveAttribute('src', '/fe-api/tasking/api/requests/request-1/simulation-result/sources/receipt%2F1/preview')
    fireEvent.error(screen.getByRole('img'))
    expect(screen.getByRole('alert')).toHaveTextContent('진단 PNG를 불러오지 못했습니다')
  })

  it.each([
    [404, '조회 가능한 합성 결과가 없습니다'],
    [403, '결과 조회 권한을 확인하세요'],
    [503, '[connection]'],
  ])('distinguishes HTTP %s without inventing product data', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, statusText: 'error', json: async () => ({}) }))
    render(<ProductPanel demoMode={false} requestId="request-1" offset={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('does not show a late result from a previous selected request', async () => {
    let finish!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(new Promise((resolve) => { finish = resolve })).mockResolvedValueOnce(ok(result('request-2'))))
    const { rerender } = render(<ProductPanel demoMode={false} requestId="request-1" offset={null} />)
    rerender(<ProductPanel demoMode={false} requestId="request-2" offset={null} />)
    await screen.findByRole('link', { name: '합성 원본 다운로드' })
    await act(async () => { finish(ok(result('request-1'))) })
    expect(screen.getByRole('link', { name: '합성 원본 다운로드' })).toHaveAttribute('href', expect.stringContaining('/request-2/'))
  })

  it('removes live links immediately when switching to demo', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(result())))
    const { rerender } = render(<ProductPanel demoMode={false} requestId="request-1" offset={null} />)
    await screen.findByRole('link', { name: '합성 원본 다운로드' })
    rerender(<ProductPanel demoMode requestId="request-1" offset={null} />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it.each(['wrong-request', 'wrong-environment'])('rejects mismatched response: %s', async (kind) => {
    const data = result(); if (kind === 'wrong-request') data.body.requestId = 'another-request'
    else data.body.environment = 'FLIGHT'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(data)))
    render(<ProductPanel demoMode={false} requestId="request-1" offset={null} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('일치하지 않아')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
