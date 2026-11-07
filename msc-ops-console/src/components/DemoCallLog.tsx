import { useEffect, useState } from 'react'
import { subscribeDemoCalls, type DemoCall } from '../demo/backend'

// Shows which service each click would reach, so the demo also explains how the services connect.
export function DemoCallLog() {
  const [calls, setCalls] = useState<DemoCall[]>([])
  useEffect(() => subscribeDemoCalls(setCalls), [])
  return (
    <details className="demo-call-log">
      <summary>
        서비스 호출 기록 {calls[0] && <>— 최근: <b>{calls[0].service}</b> {calls[0].method} {calls[0].path} → {calls[0].status}</>}
      </summary>
      <ol>
        {calls.map((call) => (
          <li key={call.seq} className={call.status >= 400 ? 'status-warn' : undefined}>
            <b>{call.service}</b> <span className="mono">{call.method} {call.path}</span> → {call.status}
          </li>
        ))}
      </ol>
    </details>
  )
}
