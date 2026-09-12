import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EvidenceSummary } from './EvidenceSummary'
describe('evidence meaning', () => {
  it('does not invent a passing status for missing evidence', () => {
    render(<EvidenceSummary data={{}} />)
    expect(screen.getByText(/세부 내용을 확인/)).toBeInTheDocument()
    expect(screen.queryByText('COMPLETE')).not.toBeInTheDocument()
  })
  it('shows the assigned run and candidate, ground booking, and original execution status', () => {
    render(<EvidenceSummary data={{ body: {
      prepared: { sources: { schedule: { assignments: [{ requestId: { value: 'request-1' }, runId: { value: 'run-committed' }, candidateId: { value: 'candidate-committed' } }] } } },
      ledger: { body: { entries: [{ status: 'PENDING', catalog: { template: { operation: 'DOWNLINK' } } }] } },
      acquisitionManifest: { body: { expected: [{ planId: 'plan-1', plan: { body: { booking: { id: 'booking-1', status: 'CONFIRMED', request: { stationId: 'station-1', requestedMegabytes: 1 } } } } }] } },
    } }} />)
    expect(screen.getByText('run-committed')).toBeInTheDocument()
    expect(screen.getByText('candidate-committed')).toBeInTheDocument()
    expect(screen.getByText(/PENDING/)).toBeInTheDocument()
    expect(screen.getByText('station-1')).toBeInTheDocument()
  })
})
