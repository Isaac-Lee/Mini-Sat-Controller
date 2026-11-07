import type { MissionInstant } from '../api/types'
import { formatServerUtc, formatTai, localOfServerUtc, useExactUtc, type TaiUtcOffset } from '../time'

interface Props {
  instant: MissionInstant
  // ponytail: unused since UTC is converted exactly server-side; kept so the many callers need no
  // change in this PR. Drop it from callers in a follow-up.
  offset?: TaiUtcOffset | null
}

// UTC is shown as primary (per operating requirement), converted by Flight Dynamics through the
// pinned leap-second archive; TAI is the authoritative raw value; local time is secondary. If the
// conversion is unavailable we say so instead of guessing.
export function TimeValue({ instant }: Props) {
  const { utc, failed } = useExactUtc(instant)
  if (!utc) {
    return (
      <span className="time-value">
        <span className="mono">{formatTai(instant)}</span>
        <span className="hint">{failed ? ' · UTC 변환 불가(변환 서비스 응답 없음)' : ' · UTC 변환 중...'}</span>
      </span>
    )
  }
  const local = localOfServerUtc(utc)
  return (
    <span className="time-value">
      {formatServerUtc(utc)}
      {local && <span className="hint"> ({local})</span>}
      <span className="hint mono"> · 원본 {formatTai(instant)}</span>
    </span>
  )
}
