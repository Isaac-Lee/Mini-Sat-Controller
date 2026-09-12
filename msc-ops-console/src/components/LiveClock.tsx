import { useEffect, useState } from 'react'

// Wall-clock "now" — this is genuinely UTC already (JS Date is UTC internally), no TAI
// conversion involved. UTC is shown first per operating requirement, local time secondary.
export function LiveClock() {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(id)
  }, [])

  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
  return (
    <span className="live-clock mono" aria-label="현재 시각">
      {now.toISOString().replace('.000Z', 'Z').replace('T', ' ')} UTC
      <span className="hint"> ({now.toLocaleTimeString()} {tz})</span>
    </span>
  )
}
