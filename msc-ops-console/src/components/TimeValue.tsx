import type { MissionInstant } from '../api/types'
import { formatLocal, formatTai, formatUtc, taiToApproxUtcMs, type TaiUtcOffset } from '../time'

interface Props {
  instant: MissionInstant
  offset: TaiUtcOffset | null
}

// UTC is shown as primary (per operating requirement), TAI is the authoritative raw value the
// backend actually returned, and local time is secondary. When no offset is available yet, UTC
// cannot be derived and we say so instead of guessing.
export function TimeValue({ instant, offset }: Props) {
  if (!offset) {
    return (
      <span className="time-value">
        <span className="mono">{formatTai(instant)}</span>
        <span className="hint"> · UTC 변환 불가(오프셋 없음)</span>
      </span>
    )
  }
  const ms = taiToApproxUtcMs(instant, offset)
  return (
    <span className="time-value">
      {formatUtc(ms)}
      <span className="hint"> ({formatLocal(ms)})</span>
      <span className="hint mono"> · 원본 {formatTai(instant)}</span>
      <span className="hint"> · UTC 근사(오프셋 기준 {offset.anchorUtcIso})</span>
    </span>
  )
}
