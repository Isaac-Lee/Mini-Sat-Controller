import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MissionActionsPanel } from './MissionActionsPanel'

describe('MissionActionsPanel', () => {
  it('asks for a request first', () => {
    render(<MissionActionsPanel selectedRequest={null} />)
    expect(screen.getByText(/요청 탭에서 조작할 요청을 먼저 선택하세요/)).toBeInTheDocument()
  })

  it('disables steps until their inputs exist and marks admin steps as fake simulation data', () => {
    const request = { id: 'req-1', version: 1, body: {} } as never
    render(<MissionActionsPanel selectedRequest={request} />)
    expect(screen.getByRole('button', { name: /최신 계획 run/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /simulation-commit/ })).toBeDisabled()
    expect(screen.getAllByRole('button', { name: /Simulation admin · 가짜 데이터/ }).length).toBeGreaterThan(0)
  })
})
