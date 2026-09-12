import { useEffect, useRef, useState } from 'react'
import { api, ApiError, newIdempotencyKey } from './api/client'
import type { EphemerisDocument, MissionInstant, OrbitSnapshot, RequestState, TrackedSatelliteRow } from './api/types'
import { demoDocument } from './demoData'
import { MapView } from './components/MapView'
import { Timeline } from './components/Timeline'
import { LiveClock } from './components/LiveClock'
import { RequestPanel } from './components/RequestPanel'
import { TimelinePanel } from './components/TimelinePanel'
import { MonitorPanel } from './components/MonitorPanel'
import { AuthorityPanel } from './components/AuthorityPanel'
import { OverviewPanel, type ConsoleTab } from './components/OverviewPanel'
import { WorkflowPanel } from './components/WorkflowPanel'
import { ProductPanel } from './components/ProductPanel'
import { useTaiUtcOffset } from './time'
import './App.css'

const NORAD_ID = 63229
const DISPLAY_NAME = 'SPACEEYE-T1'
const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000

type Phase = 'idle' | 'loading' | 'ready' | 'not_found' | 'auth_error' | 'connection_error' | 'error'

type Tab = ConsoleTab

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'overview', label: '전체 서비스' },
  { id: 'map', label: 'Mission Map' },
  { id: 'request', label: '요청' },
  { id: 'workflow', label: '임무 흐름' },
  { id: 'timeline', label: '타임라인' },
  { id: 'monitor', label: '모니터링' },
  { id: 'authority', label: '승인·안전' },
  { id: 'product', label: '제품' },
]

function phaseFromError(error: unknown): Phase {
  if (error instanceof ApiError) {
    if (error.kind === 'auth') return 'auth_error'
    if (error.kind === 'not_found') return 'not_found'
    if (error.kind === 'connection') return 'connection_error'
  }
  return 'error'
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return String(error)
}

// TAI/UTC seconds advance in lockstep except across an announced leap second, so within a short
// prediction window (hours) elapsed TAI seconds == elapsed UTC seconds from the known anchor.
function anchorUtcMs(anchorUtcIso: string, anchorTai: MissionInstant, target: MissionInstant): number {
  const anchorMs = Date.parse(anchorUtcIso)
  return anchorMs + (target.seconds - anchorTai.seconds) * 1000 + (target.nanos - anchorTai.nanos) / 1e6
}

export default function App() {
  const [demoMode, setDemoMode] = useState(false)
  const [tab, setTab] = useState<Tab>('overview')

  const [phase, setPhase] = useState<Phase>('idle')
  const [errorText, setErrorText] = useState<string>('')
  const [row, setRow] = useState<TrackedSatelliteRow | null>(null)
  const [snapshot, setSnapshot] = useState<OrbitSnapshot | null>(null)

  const [flightOrbit, setFlightOrbit] = useState<OrbitSnapshot | 'missing' | 'unknown'>('unknown')
  const [importBusy, setImportBusy] = useState(false)
  const [importMessage, setImportMessage] = useState<string>('')

  const [collectBusy, setCollectBusy] = useState(false)
  const [collectMessage, setCollectMessage] = useState<string>('')

  const [predictBusy, setPredictBusy] = useState(false)
  const [predictMessage, setPredictMessage] = useState<string>('')
  const [document_, setDocument_] = useState<EphemerisDocument | null>(null)
  const [anchor, setAnchor] = useState<{ utcIso: string; tai: MissionInstant } | null>(null)

  const [index, setIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  const playTimer = useRef<number | null>(null)

  const [inspectionCraft, setInspectionCraft] = useState('sim-spaceeye-t1')
  const [selectedRequest, setSelectedRequest] = useState<RequestState | null>(null)

  const taiUtcOffset = useTaiUtcOffset(!demoMode)

  const activeDoc = demoMode ? demoDocument : document_
  const track = activeDoc?.groundTrack ?? []
  const times = demoMode
    ? track.map((_, i) => new Date(1_800_000_000_000 + i * 60_000))
    : anchor
      ? track.map((p) => new Date(anchorUtcMs(anchor.utcIso, anchor.tai, p.time)))
      : []

  async function loadReferenceData() {
    setPhase('loading')
    setErrorText('')
    try {
      const rows = await api.trackedSatellites()
      const found = rows.find((r) => r.norad_id === NORAD_ID) ?? null
      setRow(found)
      const latest = await api.latestOrbit(NORAD_ID)
      setSnapshot(latest)
      setPhase('ready')
      try {
        const fd = await api.publicOrbit(latest.id)
        setFlightOrbit(fd)
      } catch (fdError) {
        if (fdError instanceof ApiError && fdError.kind === 'not_found') setFlightOrbit('missing')
        else throw fdError
      }
    } catch (error) {
      setPhase(phaseFromError(error))
      setErrorText(errorMessage(error))
      if (!(error instanceof ApiError && error.kind === 'not_found')) setSnapshot(null)
    }
  }

  useEffect(() => {
    if (!demoMode) loadReferenceData()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoMode])

  async function requestCollection() {
    setCollectBusy(true)
    setCollectMessage('')
    try {
      const result = await api.requestCollection(NORAD_ID)
      setCollectMessage('수집 요청 완료: ' + JSON.stringify(result))
      await loadReferenceData()
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'conflict') {
        setCollectMessage('수집 요청 거부(쿨다운/비활성/진행 중): ' + error.message)
      } else {
        setCollectMessage('수집 요청 실패: ' + errorMessage(error))
      }
    } finally {
      setCollectBusy(false)
    }
  }

  async function importOrbit() {
    if (!snapshot) return
    setImportBusy(true)
    setImportMessage('')
    try {
      await api.importPublicOrbit(snapshot.id, newIdempotencyKey())
      const fd = await api.publicOrbit(snapshot.id)
      setFlightOrbit(fd)
      setImportMessage('가져오기 완료')
    } catch (error) {
      setImportMessage('가져오기 실패: ' + errorMessage(error))
    } finally {
      setImportBusy(false)
    }
  }

  async function generatePrediction() {
    if (flightOrbit === 'unknown' || flightOrbit === 'missing') return
    setPredictBusy(true)
    setPredictMessage('')
    try {
      const nowUtc = new Date().toISOString().replace(/\.\d+Z$/, '')
      const laterUtc = new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString().replace(/\.\d+Z$/, '')
      const start = await api.utcToTai(nowUtc)
      const end = await api.utcToTai(laterUtc)
      const key = newIdempotencyKey()
      const manifestResult = await api.predict(flightOrbit.id, { start, end }, 60, key)
      const doc = await api.samples(manifestResult.body.id)
      setDocument_(doc)
      setAnchor({ utcIso: nowUtc + 'Z', tai: start })
      setIndex(0)
      setPredictMessage('예측 생성 완료: ' + doc.groundTrack.length + '개 샘플')
    } catch (error) {
      setPredictMessage('예측 생성 실패: ' + errorMessage(error))
    } finally {
      setPredictBusy(false)
    }
  }

  function togglePlay() {
    setPlaying((p) => !p)
  }

  useEffect(() => {
    if (!playing) {
      if (playTimer.current) window.clearInterval(playTimer.current)
      return
    }
    playTimer.current = window.setInterval(() => {
      setIndex((i) => {
        if (i >= times.length - 1) {
          setPlaying(false)
          return i
        }
        return i + 1
      })
    }, 250)
    return () => {
      if (playTimer.current) window.clearInterval(playTimer.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing, times.length])

  const epochAgeMs = snapshot ? Date.now() - Date.parse(snapshot.elements.epochUtc + 'Z') : null
  const staleOrbit = epochAgeMs !== null && epochAgeMs > SEVEN_DAYS_MS

  const now = new Date()
  const coverageStartMs = times[0]?.getTime()
  const coverageEndMs = times[times.length - 1]?.getTime()
  const nowInCoverage =
    coverageStartMs !== undefined && coverageEndMs !== undefined && now.getTime() >= coverageStartMs && now.getTime() <= coverageEndMs

  return (
    <div className="app">
      <header className="topbar">
        <h1>MSC Mission Console</h1>
        <div className="target-chip">공개 궤도: {DISPLAY_NAME} / NORAD {NORAD_ID}</div>
        <LiveClock />
        <label className="demo-toggle">
          <input type="checkbox" checked={demoMode} onChange={(e) => setDemoMode(e.target.checked)} />
          데모 모드
        </label>
      </header>

      {demoMode && <div className="demo-banner">데모 모드 — 실제 데이터 아님. 모든 탭이 고정 예시만 표시합니다.</div>}

      <nav className="tabbar" role="tablist" aria-label="운영 콘솔 탭">
        {TABS.map((t) => (
          <button
            key={t.id}
            id={`tab-${t.id}`}
            role="tab"
            aria-selected={tab === t.id}
            aria-controls={`panel-${t.id}`}
            tabIndex={tab === t.id ? 0 : -1}
            className={`tab-button ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
            onKeyDown={(e) => {
              const i = TABS.findIndex((x) => x.id === t.id)
              let next: Tab | null = null
              if (e.key === 'ArrowRight') next = TABS[(i + 1) % TABS.length].id
              if (e.key === 'ArrowLeft') next = TABS[(i - 1 + TABS.length) % TABS.length].id
              if (next) {
                e.preventDefault()
                setTab(next)
                document.getElementById(`tab-${next}`)?.focus()
              }
            }}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {tab === 'overview' && <main className="layout single overview-layout" id="panel-overview" role="tabpanel" aria-labelledby="tab-overview"><OverviewPanel onNavigate={setTab} /></main>}
      {tab === 'workflow' && <main className="layout single overview-layout" id="panel-workflow" role="tabpanel" aria-labelledby="tab-workflow"><WorkflowPanel onInspectCraft={(craft, safety) => { setInspectionCraft(craft); setTab(safety ? 'authority' : 'monitor') }} demoMode={demoMode} selectedRequest={selectedRequest} offset={taiUtcOffset.offset} /></main>}

      {tab === 'map' && (
        <main className="layout" id="panel-map" role="tabpanel" aria-labelledby="tab-map">
          <div className="map-pane">
            <MapView track={track} currentIndex={times.length ? index : null} requestArea={demoMode ? null : selectedRequest?.body.area ?? null} />
          </div>

          <aside className="side-panel">
            {!demoMode && (
              <section className="panel-block">
                <h2>위성 선택</h2>
                <div className="satellite-row selected">{DISPLAY_NAME} (NORAD {NORAD_ID})</div>
                <button onClick={loadReferenceData} disabled={phase === 'loading'} aria-busy={phase === 'loading'}>
                  조회 새로고침
                </button>
              </section>
            )}

            {!demoMode && phase === 'loading' && <section className="panel-block">불러오는 중...</section>}
            {!demoMode && phase === 'auth_error' && (
              <section className="panel-block status-error" role="alert">인증 실패: {errorText}</section>
            )}
            {!demoMode && phase === 'connection_error' && (
              <section className="panel-block status-error" role="alert">서비스 연결 실패: {errorText}</section>
            )}
            {!demoMode && phase === 'not_found' && (
              <section className="panel-block status-warn">데이터 없음: 수집된 공개 궤도 스냅샷이 없습니다.</section>
            )}
            {!demoMode && phase === 'error' && <section className="panel-block status-error" role="alert">오류: {errorText}</section>}

            {!demoMode && snapshot && (
              <section className="panel-block">
                <h2>공개 궤도 스냅샷</h2>
                <dl>
                  <dt>출처</dt>
                  <dd>{snapshot.provider}</dd>
                  <dt>요소 epoch (UTC)</dt>
                  <dd>{snapshot.elements.epochUtc}</dd>
                  <dt>수집 시각 (TAI seconds)</dt>
                  <dd>{snapshot.fetchedAt.seconds}</dd>
                  <dt>원본 해시</dt>
                  <dd className="mono">{snapshot.rawSha256.slice(0, 16)}...</dd>
                </dl>
                {staleOrbit && <div className="status-warn">오래된 궤도 데이터 (epoch로부터 7일 초과)</div>}
                {row?.last_error && <div className="status-error">수집 오류: {row.last_error}</div>}
              </section>
            )}

            {!demoMode && (
              <section className="panel-block">
                <h2>외부 궤도 수집</h2>
              <p><a href="/fe-api/flight/api/tracked-satellites/63229/tle.txt" download="spaceeye-t1-derived.tle">보존 GP에서 파생한 TLE 다운로드</a></p>
                <button onClick={requestCollection} disabled={collectBusy} aria-busy={collectBusy}>
                  CelesTrak 수집 요청
                </button>
                {collectMessage && <p className="hint" aria-live="polite">{collectMessage}</p>}
              </section>
            )}

            {!demoMode && snapshot && (
              <section className="panel-block">
                <h2>Flight Dynamics 가져오기</h2>
                {flightOrbit === 'missing' && (
                  <>
                    <div className="status-warn">이 스냅샷은 아직 가져오지 않았습니다.</div>
                    <button onClick={importOrbit} disabled={importBusy} aria-busy={importBusy}>
                      궤도 가져오기
                    </button>
                  </>
                )}
                {flightOrbit !== 'missing' && flightOrbit !== 'unknown' && <div className="status-ok">가져오기 완료</div>}
                {importMessage && <p className="hint" aria-live="polite">{importMessage}</p>}
              </section>
            )}

            {!demoMode && flightOrbit !== 'missing' && flightOrbit !== 'unknown' && (
              <section className="panel-block">
                <h2>지상궤적 예측</h2>
                <button onClick={generatePrediction} disabled={predictBusy} aria-busy={predictBusy}>
                  예측 생성 (향후 3시간, 60초 간격)
                </button>
                {predictMessage && <p className="hint" aria-live="polite">{predictMessage}</p>}
                {document_ && (
                  <dl>
                    <dt>모델</dt>
                    <dd>{document_.prediction.model}</dd>
                    <dt>프레임</dt>
                    <dd>{document_.prediction.frame}</dd>
                    <dt>샘플 수</dt>
                    <dd>{document_.groundTrack.length}</dd>
                  </dl>
                )}
              </section>
            )}

            <section className="panel-block">
              <h2>선택 시각 상태</h2>
              {times.length === 0 && <div className="hint">예측 데이터 없음</div>}
              {times.length > 0 && (
                <>
                  <div>선택 시각 위치 (예측값, 실측 아님)</div>
                  {!demoMode &&
                    (nowInCoverage ? (
                      <div className="status-ok">현재 시각이 예측 구간 내에 있습니다.</div>
                    ) : (
                      <div className="status-warn">
                        현재 시각은 예측 구간 밖입니다 — 표시된 위치는 실시간 위치가 아닙니다.
                      </div>
                    ))}
                </>
              )}
            </section>
          </aside>
        </main>
      )}

      {tab === 'request' && (
        <main className="layout single" id="panel-request" role="tabpanel" aria-labelledby="tab-request">
          <RequestPanel selectedRequest={selectedRequest} onOpenWorkflow={() => setTab('workflow')} demoMode={demoMode} offset={taiUtcOffset.offset} onSelectRequest={setSelectedRequest} />
        </main>
      )}

      {tab === 'timeline' && (
        <main className="layout single" id="panel-timeline" role="tabpanel" aria-labelledby="tab-timeline">
          <TimelinePanel
            demoMode={demoMode}
            offset={taiUtcOffset.offset}
            selectedRequest={selectedRequest}
            flightOrbit={flightOrbit}
          />
        </main>
      )}

      {tab === 'monitor' && (
        <main className="layout single" id="panel-monitor" role="tabpanel" aria-labelledby="tab-monitor">
          <MonitorPanel key={inspectionCraft} initialSpacecraftId={inspectionCraft} demoMode={demoMode} offset={taiUtcOffset.offset} />
        </main>
      )}

      {tab === 'authority' && (
        <main className="layout single" id="panel-authority" role="tabpanel" aria-labelledby="tab-authority">
          <AuthorityPanel key={inspectionCraft} initialSpacecraftId={inspectionCraft} demoMode={demoMode} offset={taiUtcOffset.offset} />
        </main>
      )}

      {tab === 'product' && (
        <main className="layout single" id="panel-product" role="tabpanel" aria-labelledby="tab-product">
          <ProductPanel demoMode={demoMode} requestId={selectedRequest?.id ?? null} offset={taiUtcOffset.offset} />
        </main>
      )}

      {tab === 'map' && (
        <footer className="timeline-bar">
          <Timeline
            times={times}
            index={Math.min(index, Math.max(times.length - 1, 0))}
            onIndexChange={(i) => {
              setPlaying(false)
              setIndex(i)
            }}
            playing={playing}
            onTogglePlay={togglePlay}
            disabled={times.length === 0}
          />
        </footer>
      )}
    </div>
  )
}
