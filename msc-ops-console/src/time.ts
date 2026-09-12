import { useEffect, useState } from 'react'
import { api } from './api/client'
import type { MissionInstant } from './api/types'

// Every mission timestamp the backend returns (createdAt, deadline, evaluatedAt, observedAt,
// grantedAt/validUntil, TimeWindow bounds, ...) is TAI. There is no TAI->UTC HTTP endpoint
// (only the reverse: POST /internal/time/utc-to-tai) — see docs/architecture/time-model.md and
// docs/frontend/backend-gaps.md. We derive an approximate UTC offset once from that endpoint and
// reuse it; it is not a certified conversion, so every derived UTC value is labelled "approx".

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
// `enabled=false` (demo mode) must never call the backend — every panel is required to show only
// fixture data with no live fallback while demo mode is on.
export function useTaiUtcOffset(enabled: boolean): TaiUtcOffsetState {
  const [offset, setOffset] = useState<TaiUtcOffset | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (!enabled) return
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
  }, [tick, enabled])

  return {
    offset: enabled ? offset : null,
    loading: enabled && loading,
    error: enabled ? error : null,
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
