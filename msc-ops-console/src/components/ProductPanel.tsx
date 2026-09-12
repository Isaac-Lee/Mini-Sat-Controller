import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { productsApi, type SimulationResult } from '../api/products'
import type { TaiUtcOffset } from '../time'
import { TimeValue } from './TimeValue'

interface Props {
  demoMode: boolean
  requestId: string | null
  offset: TaiUtcOffset | null
}

type LoadState =
  | { phase: 'loading' }
  | { phase: 'ready'; result: SimulationResult }
  | { phase: 'error'; message: string }

// Keyed by request and mode below: a previous request's result cannot appear under a new ID.
function RequestResult({ requestId, offset }: { requestId: string; offset: TaiUtcOffset | null }) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' })
  const [reload, setReload] = useState(0)
  const [failedPreviews, setFailedPreviews] = useState<string[]>([])

  useEffect(() => {
    let active = true
    productsApi.result(requestId).then(
      ({ body }) => {
        if (!active) return
        if (body.requestId !== requestId || body.environment !== 'SIMULATION') {
          setState({ phase: 'error', message: '요청 또는 SIMULATION 환경이 일치하지 않아 결과를 표시할 수 없습니다.' })
          return
        }
        setState({ phase: 'ready', result: body })
      },
      (error: unknown) => {
        if (!active) return
        const message = error instanceof ApiError
          ? error.kind === 'not_found'
            ? '조회 가능한 합성 결과가 없습니다. 아직 등록되지 않았거나 이 요청에 접근할 수 없습니다.'
            : error.kind === 'auth'
              ? '결과 조회 권한을 확인하세요.'
              : `[${error.kind}] ${error.message}`
          : '결과 조회에 실패했습니다.'
        setState({ phase: 'error', message })
      },
    )
    return () => { active = false }
  }, [requestId, reload])

  return (
    <>
      <p className="mono">요청 {requestId}</p>
      <button onClick={() => { setState({ phase: 'loading' }); setFailedPreviews([]); setReload((n) => n + 1) }} disabled={state.phase === 'loading'}>결과 새로고침</button>
      {state.phase === 'loading' && <p role="status">합성 결과 불러오는 중...</p>}
      {state.phase === 'error' && <p className="status-warn" role="alert">{state.message}</p>}
      {state.phase === 'ready' && (
        <div aria-live="polite">
          <dl>
            <dt>환경 · 상태</dt><dd>{state.result.environment} · {state.result.status}</dd>
            <dt>완료 근거</dt><dd>{state.result.completionBasis}</dd>
            <dt>요청 리비전</dt><dd>{state.result.requestRevision}</dd>
            <dt>제품 ID</dt><dd className="mono">{state.result.productId}</dd>
            <dt>시나리오</dt><dd>{state.result.scenarioId}</dd>
            <dt>촬영 명령 / 다운링크 명령</dt><dd>{state.result.imageLoadId} / {state.result.downlinkLoadId}</dd>
            <dt>검토자 · 검토 참조</dt><dd>{state.result.reviewer} · {state.result.reviewReference}</dd>
            <dt>완료 시각</dt><dd><TimeValue instant={state.result.completedAt} offset={offset} /></dd>
          </dl>
          <details>
            <summary>완료 판정에 사용된 출처 해시</summary>
            <dl>{Object.entries(state.result.sourceHashes).map(([name, hash]) => (
              <div key={name}><dt>{name}</dt><dd className="mono">{hash}</dd></div>
            ))}</dl>
          </details>
          <h3>합성 원본과 진단 PNG</h3>
          {state.result.sources.length === 0 && <p>등록된 원본이 없습니다.</p>}
          {state.result.sources.map((source) => (
            <article className="panel-block" key={source.receiptId}>
              <h4>수신 {source.receiptId}</h4>
              <dl>
                <dt>페이로드</dt><dd>{source.payloadId}</dd>
                <dt>원본 크기</dt><dd>{source.byteCount.toLocaleString()} bytes</dd>
                <dt>원본 SHA-256 (서버 기록)</dt><dd className="mono">{source.sha256}</dd>
                <dt>PNG 크기</dt><dd>{source.previewByteCount.toLocaleString()} bytes</dd>
                <dt>PNG SHA-256 (서버 기록)</dt><dd className="mono">{source.previewSha256}</dd>
              </dl>
              <p><a href={productsApi.sourceUrl(requestId, source.receiptId)} download>합성 원본 다운로드</a></p>
              <p><a href={productsApi.sourceUrl(requestId, source.receiptId, true)} download>진단 PNG 다운로드</a></p>
              {failedPreviews.includes(source.receiptId) ? (
                <p role="alert">진단 PNG를 불러오지 못했습니다. 결과 새로고침으로 재시도할 수 있습니다.</p>
              ) : (
                <img
                  src={productsApi.sourceUrl(requestId, source.receiptId, true)}
                  alt={`수신 ${source.receiptId} 합성 바이트 진단 표시 — 실제 위성 영상 아님`}
                  style={{ maxWidth: '100%', imageRendering: 'pixelated' }}
                  onError={() => setFailedPreviews((ids) => [...ids, source.receiptId])}
                />
              )}
            </article>
          ))}
        </div>
      )}
    </>
  )
}

export function ProductPanel({ demoMode, requestId, offset }: Props) {
  return (
    <section className="panel-block product-panel">
      <h2>제품 · 합성 결과</h2>
      <p className="hint">SIMULATION V1 완료는 합성 실행·다운링크·제품 출처의 연결을 뜻합니다. 실제 위성 영상이나 물리 영상 품질·AOI 커버리지 충족을 의미하지 않습니다.</p>
      {demoMode ? (
        <div className="demo-banner">데모 모드 — 실제 결과와 다운로드는 조회하지 않습니다. 요청을 선택하고 데모 모드를 끄면 서버 결과를 볼 수 있습니다.</div>
      ) : requestId ? (
        <RequestResult key={requestId} requestId={requestId} offset={offset} />
      ) : (
        <p className="hint">요청 탭에서 요청을 선택하면 등록된 합성 결과를 조회합니다.</p>
      )}
    </section>
  )
}
