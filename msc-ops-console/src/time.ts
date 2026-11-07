import { useEffect, useState } from 'react'
import { api } from './api/client'
import type { MissionInstant } from './api/types'

// Every mission timestamp the backend returns is TAI. Displayed UTC comes from Flight Dynamics'
// POST /api/time/tai-to-utc, which converts through the pinned leap-second archive; the console
// never derives UTC itself (docs/architecture/time-model.md). The single "now" offset below is
// kept only for ground-track playback animation, which labels itself approximate.

export function formatTai(instant: MissionInstant): string {
  const frac = String(instant.nanos).padStart(9, '0')
  return `${instant.seconds}.${frac} TAI`
}

export interface TaiUtcOffset {
  offsetMs: number
  anchorUtcIso: string
}

export interface TaiUtcOffsetState {
  offset: TaiUtcOffset | null
  loading: boolean
  error: string | null
  refresh: () => void
}

// TAI and UTC seconds advance in lockstep except across an announced leap second, so this
// offset is only trustworthy for instants close in time to when it was captured (see the same
// caveat already used for ground-track playback in App.tsx).
export function useTaiUtcOffset(): TaiUtcOffsetState {
  const [offset, setOffset] = useState<TaiUtcOffset | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    async function fetchOffset() {
      setLoading(true)
      setError(null)
      const nowUtcNoZ = new Date().toISOString().replace(/\.\d+Z$/, '')
      const nowUtcIso = nowUtcNoZ + 'Z'
      try {
        const tai = await api.utcToTai(nowUtcNoZ)
        if (cancelled) return
        const offsetMs = Date.parse(nowUtcIso) - tai.seconds * 1000 - tai.nanos / 1e6
        setOffset({ offsetMs, anchorUtcIso: nowUtcIso })
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchOffset()
    return () => {
      cancelled = true
    }
  }, [tick])

  return {
    offset,
    loading,
    error,
    refresh: () => setTick((t) => t + 1),
  }
}

export function taiToApproxUtcMs(instant: MissionInstant, offset: TaiUtcOffset): number {
  return instant.seconds * 1000 + instant.nanos / 1e6 + offset.offsetMs
}

export function formatUtc(ms: number): string {
  return new Date(ms).toISOString().replace('.000Z', 'Z').replace('T', ' ') + ' UTC'
}

export function formatLocal(ms: number): string {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
  return new Date(ms).toLocaleString() + ` (${tz})`
}

// Exact UTC for displayed instants: requests made in the same tick are sent as one batch and
// answers are cached for the page's lifetime (TAI instants are immutable).
const MAX_BATCH = 500
const utcCache = new Map<string, Promise<string>>()
let pending: Array<{ instant: MissionInstant; resolve: (utc: string) => void; reject: (e: unknown) => void }> = []

const cacheKey = (i: MissionInstant) => `${i.seconds}:${i.nanos}`

async function flush() {
  const batch = pending.splice(0, MAX_BATCH)
  if (pending.length) queueMicrotask(flush)
  try {
    const result = await api.convertTai(batch.map((b) => b.instant))
    batch.forEach((b, n) => {
      const row = result.conversions[n]
      if (row && row.tai.seconds === b.instant.seconds && row.tai.nanos === b.instant.nanos) b.resolve(row.utc)
      else b.reject(new Error('Conversion response mismatch'))
    })
  } catch (error) {
    batch.forEach((b) => b.reject(error))
  }
}

export function exactUtc(instant: MissionInstant): Promise<string> {
  const key = cacheKey(instant)
  let hit = utcCache.get(key)
  if (!hit) {
    hit = new Promise<string>((resolve, reject) => {
      if (pending.length === 0) queueMicrotask(flush)
      pending.push({ instant, resolve, reject })
    })
    // A failure is not cached, so a later render can retry.
    hit.catch(() => utcCache.delete(key))
    utcCache.set(key, hit)
  }
  return hit
}

export function useExactUtc(instant: MissionInstant): { utc: string | null; failed: boolean } {
  const [state, setState] = useState<{ key: string; utc: string | null; failed: boolean }>({ key: '', utc: null, failed: false })
  const key = cacheKey(instant)
  useEffect(() => {
    let current = true
    exactUtc(instant).then(
      (utc) => { if (current) setState({ key, utc, failed: false }) },
      () => { if (current) setState({ key, utc: null, failed: true }) },
    )
    return () => { current = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state.key === key ? state : { utc: null, failed: false }
}

// Server UTC text ("2016-12-31T23:59:60.000") rendered for people; local time is shown only when
// the instant is representable in JS Date (never for a leap second).
export function formatServerUtc(utc: string): string {
  return utc.replace('T', ' ') + ' UTC'
}
export function localOfServerUtc(utc: string): string | null {
  if (/:60(\.|$)/.test(utc)) return null
  const ms = Date.parse(utc + 'Z')
  return Number.isFinite(ms) ? formatLocal(ms) : null
}
